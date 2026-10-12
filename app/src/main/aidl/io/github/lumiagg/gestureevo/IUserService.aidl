package io.github.lumiagg.gestureevo;

import io.github.lumiagg.gestureevo.utils.ExecuteResult;

interface IUserService {
    // Shizuku sends its reserved destroy transaction to this method.
    void destroy() = 16777114;
    // Preserve the existing transaction IDs for ordinary client calls.
    void exit() = 1;
    ExecuteResult execute(in List<String> cmdarray, in List<String> envp, in String dir) = 2;
}
