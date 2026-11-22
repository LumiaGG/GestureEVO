/*
 * DB detection and CTC decoding adapted from PaddleOCR / RapidOCR.
 * Copyright (c) 2020-2026 PaddlePaddle Authors. Licensed under Apache-2.0.
 * CyIME changes: bounded strip resolution, single-crop inference, shared Android/desktop
 * implementation and spatial text assembly. CyIME contributors, GPL-3.0.
 * GestureEVO changes: screenshot detection and axis-aligned line crops.
 * See assets/licenses/ocr/PP-OCR-NOTICE.txt.
 */
package io.github.lumiagg.gestureevo.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

final class OcrPipeline implements AutoCloseable {
    // Screens can be denser than camera strips. Keep the work and IPC result bounded.
    private static final int MAX_SCREEN_REGIONS = 256;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final Function<String, ByteBuffer> mapModel;
    private final Supplier<List<String>> readCharacters;
    private ModelSession detection;
    private ModelSession recognition;
    private String[] alphabet;

    OcrPipeline(Function<String, ByteBuffer> mapModel, Supplier<List<String>> readCharacters) {
        this.mapModel = mapModel;
        this.readCharacters = readCharacters;
    }

    private final class ModelSession implements AutoCloseable {
        // The mapping and options must outlive the native session.
        private final ByteBuffer weights;
        private final OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        private final OrtSession session;
        private final String inputName;

        ModelSession(ByteBuffer weights) throws OrtException {
            this.weights = weights;
            OrtSession created = null;
            try {
                options.setIntraOpNumThreads(2);
                options.setInterOpNumThreads(1);
                options.addConfigEntry("session.intra_op.allow_spinning", "0");
                options.addConfigEntry("session.inter_op.allow_spinning", "0");
                options.setMemoryPatternOptimization(false);
                created = environment.createSession(this.weights, options);
                inputName = created.getInputNames().iterator().next();
                session = created;
            } catch (OrtException | RuntimeException error) {
                try {
                    if (created != null) created.close();
                } catch (OrtException closeError) {
                    error.addSuppressed(closeError);
                } finally {
                    options.close();
                }
                throw error;
            }
        }

        @Override public void close() throws OrtException {
            try { session.close(); } finally { options.close(); }
        }
    }

    void warmDetection(OcrRequest request) throws OrtException {
        detection(request);
    }

    private ModelSession detection(OcrRequest request) throws OrtException {
        request.ensureActive();
        if (detection == null) detection = new ModelSession(mapModel.apply("ch_PP-OCRv5_det_mobile.onnx"));
        return detection;
    }

    private ModelSession recognition(OcrRequest request) throws OrtException {
        request.ensureActive();
        if (recognition == null) {
            List<String> chars = new ArrayList<>();
            chars.add(""); // CTC blank
            chars.addAll(readCharacters.get());
            chars.add(" ");
            alphabet = chars.toArray(new String[0]);
            recognition = new ModelSession(mapModel.apply("ch_PP-OCRv5_rec_mobile.onnx"));
        }
        return recognition;
    }

    static final class TextRegion {
        public final Point[] points;
        public final String text;
        TextRegion(Point[] points, String text) {
            this.points = points; this.text = text;
        }
        double centerY() { return Arrays.stream(points).mapToDouble(p -> p.y).average().orElse(0); }
        double height() { return Math.max(distance(points[0], points[3]), distance(points[1], points[2])); }
    }

    List<TextRegion> detectRegions(Mat bgr, OcrRequest request) throws OrtException {
        List<TextRegion> regions = new ArrayList<>();
        for (Point[] box : detectBoxes(bgr, request)) regions.add(new TextRegion(box, ""));
        return readingOrder(regions);
    }

    List<TextRegion> recognize(Mat bgr, OcrRequest request) throws OrtException {
        List<Point[]> boxes = detectBoxes(bgr, request);
        List<TextRegion> text = new ArrayList<>();
        List<TextRegion> candidates = new ArrayList<>();
        for (Point[] box : boxes) candidates.add(new TextRegion(box, ""));
        List<TextRegion> ordered = readingOrder(candidates);
        for (TextRegion candidate : ordered.subList(0, Math.min(MAX_SCREEN_REGIONS, ordered.size()))) {
            request.ensureActive();
            Mat crop = bgr.submat(bounds(candidate.points, bgr.cols(), bgr.rows()));
            try {
                Line line = read(crop, request);
                if (line.accepted()) text.add(new TextRegion(candidate.points, line.text));
            } finally {
                crop.release();
            }
        }
        // The upstream isolated-symbol recovery is designed for horizontal strips.
        if (bgr.cols() >= 3L * bgr.rows() && bgr.total() <= 1_048_576) {
            recoverIsolatedMarks(bgr, boxes, text, request);
        }
        return readingOrder(text);
    }

    String recognizeLine(Mat bgr, OcrRequest request) throws OrtException {
        Line line = read(bgr, request);
        return line.accepted() ? line.text : "";
    }

    private List<Point[]> detectBoxes(Mat bgr, OcrRequest request) throws OrtException {
        double scale = Math.max(1, Math.max(320.0 / Math.min(bgr.rows(), bgr.cols()),
                2048.0 / Math.max(bgr.rows(), bgr.cols())));
        // Excessive enlargement of small screenshot crops can fragment DB boxes.
        scale = Math.min(scale, Math.min(2, 2560.0 / Math.max(bgr.rows(), bgr.cols())));
        scale = Math.min(scale, Math.sqrt(1_048_576.0 / bgr.total()));
        int height = multiple32(bgr.rows() * scale);
        int width = multiple32(bgr.cols() * scale);
        return detect(run(detection(request), normalize(bgr, width, height, width, true),
                new long[]{1, 3, height, width}, request), bgr.cols(), bgr.rows());
    }

    static Rect bounds(Point[] points, int width, int height) {
        double left = width, top = height, right = 0, bottom = 0;
        for (Point point : points) {
            left = Math.min(left, point.x);
            top = Math.min(top, point.y);
            right = Math.max(right, point.x);
            bottom = Math.max(bottom, point.y);
        }
        int x = Math.max(0, (int)Math.floor(left));
        int y = Math.max(0, (int)Math.floor(top));
        return new Rect(x, y, Math.min(width, (int)Math.ceil(right)) - x,
                Math.min(height, (int)Math.ceil(bottom)) - y);
    }

    private static final class Line {
        final String text; final float confidence;
        Line(String text, float confidence) { this.text = text; this.confidence = confidence; }
        boolean accepted() { return !text.isEmpty() && confidence >= .65f; }
    }

    private Line read(Mat crop, OcrRequest request) throws OrtException {
        int contentWidth = Math.max(1, Math.min(1600, (int)Math.ceil(48.0 * crop.cols() / crop.rows())));
        int tensorWidth = Math.max(320, contentWidth);
        TensorOutput output = run(recognition(request), normalize(crop, contentWidth, 48, tensorWidth, false),
                new long[]{1, 3, 48, tensorWidth}, request);
        if (output.shape.length != 3 || output.shape[2] != alphabet.length) {
            throw new IllegalArgumentException("OCR alphabet " + alphabet.length + " does not match output " + Arrays.toString(output.shape));
        }
        StringBuilder value = new StringBuilder();
        float confidence = 0;
        int count = 0, previous = -1, classes = alphabet.length;
        for (int t = 0; t < output.shape[1]; t++) {
            int best = 0, offset = t * classes;
            for (int c = 1; c < classes; c++) if (output.data[offset + c] > output.data[offset + best]) best = c;
            if (best != 0 && best != previous) {
                value.append(alphabet[best]); confidence += output.data[offset + best]; count++;
            }
            previous = best;
        }
        return new Line(value.toString().trim(), count > 0 ? confidence / count : 0);
    }

    /**
     * PP-OCR's DB detector is trained on text lines and gives no box to lone marks such as the
     * "<", "," legends on keycaps, although the recognizer reads them inside a line. Find small
     * high-contrast marks that no accepted text covers and add the symbols read for them.
     */
    private void recoverIsolatedMarks(Mat bgr, List<Point[]> boxes, List<TextRegion> text, OcrRequest request) throws OrtException {
        int rows = bgr.rows(), cols = bgr.cols();
        List<Rect> marks = new ArrayList<>();
        int k = Math.max(9, (int)(Math.min(rows, cols) * .3)) | 1;
        Mat gray = new Mat(), map = new Mat(), otsu = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(k, k));
        Mat labels = new Mat(), stats = new Mat(), centroids = new Mat();
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            // Thin strokes darker than a light surface (or lighter than a dark one). Only one
            // polarity: the other turns whole key faces around a legend into false marks.
            MatOfDouble mean = new MatOfDouble(), deviation = new MatOfDouble();
            try { Core.meanStdDev(gray, mean, deviation); } finally { deviation.release(); }
            boolean lightSurface = mean.get(0, 0)[0] >= 128;
            mean.release();
            Imgproc.morphologyEx(gray, map, lightSurface ? Imgproc.MORPH_BLACKHAT : Imgproc.MORPH_TOPHAT, kernel);
            double level = Imgproc.threshold(map, otsu, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.threshold(map, map, Math.max(48, level), 255, Imgproc.THRESH_BINARY);
            int count = Imgproc.connectedComponentsWithStats(map, labels, stats, centroids, 8, CvType.CV_32S);
            for (int i = 1; i < Math.min(count, 400); i++) {
                int x = (int)stats.get(i, Imgproc.CC_STAT_LEFT)[0], y = (int)stats.get(i, Imgproc.CC_STAT_TOP)[0];
                int w = (int)stats.get(i, Imgproc.CC_STAT_WIDTH)[0], h = (int)stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
                // Glyph-sized only: keycap edges are thin slivers and sensor specks a few pixels,
                // and each surviving row costs one more recognizer pass on the device.
                if (h < Math.max(5, rows * .05) || h > rows * .45 || w > cols * .25 || w > h * 4 || h > w * 8) continue;
                if (x == 0 || y == 0 || x + w >= cols || y + h >= rows) continue;
                Point center = new Point(x + w / 2.0, y + h / 2.0);
                boolean covered = false;
                for (Point[] box : boxes) {
                    MatOfPoint2f polygon = new MatOfPoint2f(box);
                    try { covered = Imgproc.pointPolygonTest(polygon, center, false) >= 0; } finally { polygon.release(); }
                    if (covered) break;
                }
                if (!covered) marks.add(new Rect(x, y, w, h));
            }
        } finally {
            kernel.release(); centroids.release(); stats.release(); labels.release();
            otsu.release(); map.release(); gray.release();
        }
        // A lone glyph has no line context ("<" reads as "く", ">" as "V"). Read each row of marks
        // together with the detected words beside it, and keep only the symbols the detector
        // missed: detected text is never re-read, replaced or duplicated.
        marks.sort(Comparator.comparingDouble(r -> r.y + r.height / 2.0));
        int rowsRead = 0;
        while (!marks.isEmpty() && rowsRead++ < 2) {
            Rect seed = marks.remove(0);
            List<Rect> row = new ArrayList<>(); row.add(seed);
            int top = seed.y, bottom = seed.y + seed.height;
            for (int i = 0; i < marks.size();) {
                Rect r = marks.get(i);
                double center = r.y + r.height / 2.0, slack = (bottom - top) * .5;
                if (center >= top - slack && center <= bottom + slack) {
                    row.add(r); marks.remove(i);
                    top = Math.min(top, r.y); bottom = Math.max(bottom, r.y + r.height);
                } else i++;
            }
            row.sort(Comparator.comparingInt(r -> r.x));
            int left = row.get(0).x, right = row.get(row.size() - 1).x + row.get(row.size() - 1).width;
            double slack = (bottom - top) * .5;
            StringBuilder known = new StringBuilder();
            for (TextRegion region : text) {
                double center = region.centerY();
                if (center < top - slack || center > bottom + slack) continue;
                known.append(region.text);
                // Words widen the line; its height stays the marks' own, since detected boxes
                // are padded and can be tilted or merged with a keycap edge.
                for (Point p : region.points) {
                    left = Math.min(left, (int)p.x); right = Math.max(right, (int)Math.ceil(p.x));
                }
            }
            int pad = Math.max(2, (int)Math.round((bottom - top) * .6));
            Rect band = new Rect(new Point(Math.max(0, left - pad), Math.max(0, top - pad)),
                    new Point(Math.min(cols, right + pad), Math.min(rows, bottom + pad)));
            if (band.width < 4 || band.height < 4) continue;
            Mat crop = bgr.submat(band);
            Line line;
            try { line = read(crop, request); } finally { crop.release(); }
            if (!line.accepted()) continue;
            String missed = missedSymbols(line.text, known.toString());
            if (missed.isEmpty()) continue;
            // One region per mark when the counts agree, so reading order follows the image.
            int[] symbols = missed.codePoints().toArray();
            if (symbols.length == row.size()) {
                for (int i = 0; i < symbols.length; i++) text.add(markRegion(row.get(i), new String(symbols, i, 1)));
            } else {
                Rect all = new Rect(new Point(row.get(0).x, Math.min(row.get(0).y, row.get(row.size() - 1).y)),
                        new Point(right, Math.max(row.get(0).y + row.get(0).height, row.get(row.size() - 1).y + row.get(row.size() - 1).height)));
                text.add(markRegion(all, missed));
            }
        }
    }

    /** Symbols in the row reading that the detected words of that row do not already contain. */
    static String missedSymbols(String reading, String known) {
        // "？" in the row reading is the same mark as a detected "?".
        Map<String, Integer> have = new HashMap<>();
        known.codePoints().filter(OcrPipeline::isSymbol).forEach(c -> have.merge(width(c), 1, Integer::sum));
        StringBuilder missed = new StringBuilder();
        reading.codePoints().filter(OcrPipeline::isSymbol).forEach(c -> {
            if (have.getOrDefault(width(c), 0) > 0) have.merge(width(c), -1, Integer::sum);
            else missed.appendCodePoint(c);
        });
        return missed.toString();
    }

    private static String width(int c) { return Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFKC); }

    private static boolean isSymbol(int c) { return !Character.isLetterOrDigit(c) && !Character.isWhitespace(c); }

    private static TextRegion markRegion(Rect r, String text) {
        return new TextRegion(new Point[]{new Point(r.x, r.y), new Point(r.x + r.width, r.y),
                new Point(r.x + r.width, r.y + r.height), new Point(r.x, r.y + r.height)}, text);
    }

    private static List<TextRegion> readingOrder(List<TextRegion> regions) {
        regions.sort(Comparator.comparingDouble(TextRegion::centerY));
        List<TextRegion> ordered = new ArrayList<>();
        while (!regions.isEmpty()) {
            TextRegion anchor = regions.remove(0);
            List<TextRegion> line = new ArrayList<>(); line.add(anchor);
            for (int i = 0; i < regions.size();) {
                if (sameLine(anchor, regions.get(i))) line.add(regions.remove(i)); else i++;
            }
            line.sort(Comparator.comparingDouble(r -> r.points[0].x));
            ordered.addAll(line);
        }
        return ordered;
    }

    private static boolean sameLine(TextRegion a, TextRegion b) {
        return Math.abs(a.centerY() - b.centerY()) < Math.max(a.height(), b.height()) * .65;
    }

    private static int multiple32(double size) { return Math.max(32, (int)Math.rint(size / 32) * 32); }
    private static double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.y - b.y); }

    private static float[] normalize(Mat src, int width, int height, int paddedWidth, boolean detection) {
        Mat resized = new Mat();
        try {
            Imgproc.resize(src, resized, new Size(width, height), 0, 0, Imgproc.INTER_LINEAR);
            byte[] pixels = new byte[width * height * 3]; resized.get(0, 0, pixels);
            float[] tensor = new float[3 * height * paddedWidth];
            float[] mean = {.485f, .456f, .406f}, std = {.229f, .224f, .225f};
            for (int c = 0; c < 3; c++) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                float p = (pixels[(y * width + x) * 3 + c] & 255) / 255f;
                tensor[(c * height + y) * paddedWidth + x] = detection ? (p - mean[c]) / std[c] : (p - .5f) / .5f;
            }
            return tensor;
        } finally { resized.release(); }
    }

    private static final class TensorOutput {
        final float[] data; final long[] shape;
        TensorOutput(float[] data, long[] shape) { this.data = data; this.shape = shape; }
    }

    private TensorOutput run(ModelSession model, float[] input, long[] shape, OcrRequest request) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = model.session.run(Collections.singletonMap(model.inputName, tensor), request.runOptions())) {
            request.ensureActive();
            OnnxTensor output = (OnnxTensor) result.get(0);
            FloatBuffer buffer = output.getFloatBuffer();
            // ORT 1.28 already copies FP32 output to a heap buffer.
            float[] values;
            if (buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.position() == 0
                    && buffer.remaining() == buffer.array().length) {
                values = buffer.array();
            } else {
                values = new float[buffer.remaining()];
                buffer.get(values);
            }
            return new TensorOutput(values, output.getInfo().getShape());
        }
    }

    private static List<Point[]> detect(TensorOutput output, int originalWidth, int originalHeight) {
        int height = (int)output.shape[2], width = (int)output.shape[3];
        Mat probability = new Mat(height, width, CvType.CV_32FC1);
        Mat binary = new Mat(), mask = new Mat(), hierarchy = new Mat();
        Mat kernel = Mat.ones(2, 2, CvType.CV_8UC1);
        List<MatOfPoint> contours = new ArrayList<>();
        List<Point[]> boxes = new ArrayList<>();
        try {
            probability.put(0, 0, output.data);
            Imgproc.threshold(probability, binary, .3, 255, Imgproc.THRESH_BINARY);
            binary.convertTo(mask, CvType.CV_8UC1);
            Imgproc.dilate(mask, mask, kernel);
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            for (int i = 0; i < Math.min(1000, contours.size()); i++) {
                MatOfPoint2f contour = new MatOfPoint2f(contours.get(i).toArray());
                RotatedRect rect;
                try { rect = Imgproc.minAreaRect(contour); } finally { contour.release(); }
                if (Math.min(rect.size.width, rect.size.height) < 3) continue;
                Point[] points = new Point[4]; rect.points(points);
                if (boxScore(probability, points) < .5) continue;
                // The DB polygon is a rectangle. Its rounded polygon offset has the same
                // minimum-area rectangle as expanding both sides by the offset distance.
                double offset = rect.size.area() * 1.4 / (2 * (rect.size.width + rect.size.height));
                rect.size.width += 2 * offset; rect.size.height += 2 * offset;
                if (Math.min(rect.size.width, rect.size.height) < 5) continue;
                rect.points(points); points = orderedQuad(points);
                for (Point point : points) {
                    point.x = Math.max(0, Math.min(originalWidth, Math.rint(point.x * originalWidth / width)));
                    point.y = Math.max(0, Math.min(originalHeight, Math.rint(point.y * originalHeight / height)));
                }
                Rect roi = bounds(points, originalWidth, originalHeight);
                if (roi.width >= 2 && roi.height >= 2 && distance(points[0], points[1]) > 3
                        && distance(points[0], points[3]) > 3) boxes.add(points);
            }
        } finally {
            for (Mat contour : contours) contour.release();
            kernel.release(); hierarchy.release(); mask.release(); binary.release(); probability.release();
        }
        boxes.sort(Comparator.<Point[]>comparingDouble(b -> b[0].y).thenComparingDouble(b -> b[0].x));
        return boxes;
    }

    private static double boxScore(Mat probability, Point[] points) {
        int left = probability.cols() - 1, top = probability.rows() - 1, right = 0, bottom = 0;
        for (Point p : points) {
            left = Math.min(left, Math.max(0, (int)Math.floor(p.x)));
            top = Math.min(top, Math.max(0, (int)Math.floor(p.y)));
            right = Math.max(right, Math.min(probability.cols() - 1, (int)Math.ceil(p.x)));
            bottom = Math.max(bottom, Math.min(probability.rows() - 1, (int)Math.ceil(p.y)));
        }
        Mat mask = Mat.zeros(bottom - top + 1, right - left + 1, CvType.CV_8UC1);
        MatOfPoint polygon = new MatOfPoint();
        Mat roi = probability.submat(top, bottom + 1, left, right + 1);
        try {
            Point[] local = new Point[4];
            for (int i = 0; i < 4; i++) local[i] = new Point((int)(points[i].x - left), (int)(points[i].y - top));
            polygon.fromArray(local);
            Imgproc.fillPoly(mask, Collections.singletonList(polygon), new Scalar(1));
            return Core.mean(roi, mask).val[0];
        } finally { roi.release(); polygon.release(); mask.release(); }
    }

    private static Point[] orderedQuad(Point[] points) {
        Arrays.sort(points, Comparator.comparingDouble(p -> p.x));
        Point a = points[0], b = points[1], c = points[2], d = points[3];
        return new Point[]{a.y < b.y ? a : b, c.y < d.y ? c : d, c.y < d.y ? d : c, a.y < b.y ? b : a};
    }

    @Override public void close() throws OrtException {
        releaseRecognition();
        try (ModelSession detector = detection) {
            detection = null;
        }
        // OrtEnvironment is process-shared; closing one OCR session must not invalidate another.
    }

    void releaseRecognition() {
        ModelSession recognizer = recognition;
        recognition = null;
        alphabet = null;
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (OrtException ignored) {
            }
        }
    }
}
