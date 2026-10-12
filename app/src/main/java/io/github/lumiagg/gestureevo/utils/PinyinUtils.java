package io.github.lumiagg.gestureevo.utils;

import com.github.promeg.pinyinhelper.Pinyin;
import com.github.promeg.pinyinhelper.PinyinMapDict;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class PinyinUtils {

    private static boolean isInit = false;

    public static boolean isChinese(String input) {
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) >= 0x4E00 && input.charAt(i) <= 0x9FA5) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts lowercase initial letters from the given input (supports both pinyin and words).
     */
    public static String getPinyinFP2(String input) {
        initPinyin();
        StringBuilder ret = new StringBuilder(); // Initials
        String[] inputSplit = input.split(" ");
        if (inputSplit.length > 1) {
            for (String s : inputSplit) {
                if (isChinese(s)) {
                    for (int i = 0; i < s.length(); i++) {
                        String pinyin = Pinyin.toPinyin(s.charAt(i));
                        if (pinyin.length() > 0) {
                            ret.append(pinyin.charAt(0));
                        }
                    }
                } else {
                    if (s.length() > 0) {
                        ret.append(s.charAt(0));
                    }
                }
            }
        } else {
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (Pinyin.isChinese(c)) {
                    String pinyin = Pinyin.toPinyin(c);
                    if (pinyin.length() > 0) {
                        ret.append(pinyin.charAt(0));
                    }
                } else if (Character.isUpperCase(c)) {
                    ret.append(c);
                } else if (i == 0 && Character.isLowerCase(c)) {
                    ret.append(c);
                }
            }
        }

        return ret.toString().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public static String getPinyinFP(String input) {
        initPinyin();
        String pinyin = Pinyin.toPinyin(input, ",");
        StringBuilder ret = new StringBuilder(); // Initials
        String[] pinyinSplit = pinyin.split(",");
        for (int i = 0; i < pinyinSplit.length; i++) {
            String item = pinyinSplit[i];
            if (item.length() < 1 || item.equals(" ")) continue;
            if (i == 0) {
                ret.append(item.charAt(0));
            } else if (Character.isUpperCase(item.charAt(0))) {
                ret.append(item.charAt(0));
            } else if (item.contains(" ") && i + 1 < pinyinSplit.length) {
                ret.append(pinyinSplit[i + 1].charAt(0));
            } else if (item.contains(":")) {
                ret.append(item.charAt(0));
            }
        }

        return ret.toString().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public static String getPinyinParse(String input) {
        initPinyin();
        return Pinyin.toPinyin(input, "").toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public static void initPinyin() {
        if (isInit) return;
        isInit = true;
        // Add custom dictionary mappings
        Pinyin.init(Pinyin.newConfig()
                .with(new PinyinMapDict() {
                    @Override
                    public Map<String, String[]> mapping() {
                        HashMap<String, String[]> map = new HashMap<>();
                        map.put("重庆", new String[]{"CHONG", "QING"});
                        map.put("音乐", new String[]{"YIN", "YUE"});
                        map.put("银行", new String[]{"YIN", "HANG"});
                        return map;
                    }
                }));
    }
}
