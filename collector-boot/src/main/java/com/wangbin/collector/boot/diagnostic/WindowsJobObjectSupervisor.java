package com.wangbin.collector.boot.diagnostic;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 仅供受控诊断管理器使用的 Windows Job Object 监督器。
 *
 * <p>该类不接受任意命令入口；调用方必须先把命令、输出文件和超时绑定到固定的诊断构件与实例。
 * Job Object 分配失败时按失败关闭，不用 {@code taskkill} 冒充进程树隔离。</p>
 */
public final class WindowsJobObjectSupervisor {


    private static final int WAIT_TIMEOUT = 258;
    private static final int ERROR_ACCESS_DENIED = 5;

    private WindowsJobObjectSupervisor() {
    }

    /**
     * 在一个专用 Job Object 中运行已经由上层固定和校验的子进程。
     *
     * @param command 已完成白名单和 SHA 校验的完整命令
     * @param workingDirectory 已完成所有者和重解析点校验的工作目录
     * @param outputFile 受保护且有大小限制的子进程输出文件
     * @param timeout 最大等待时长；不包含操作系统异常下的数学绝对保证
     * @return 子进程退出及 Job 清理结果
     */
    static Result run(List<String> command, Path workingDirectory, Path outputFile, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(timeout, "timeout");
        if (!isWindows()) return Result.blocked("非 Windows 环境");
        if (command.isEmpty() || timeout.isZero() || timeout.isNegative()
                || !workingDirectory.isAbsolute() || !outputFile.isAbsolute()) {
            return Result.rejected("监督参数不完整");
        }
        if (!Files.isDirectory(workingDirectory) || Files.exists(outputFile)) {
            return Result.rejected("工作目录或输出文件不符合预创建约束");
        }

        WinNT.HANDLE job = null;
        WinNT.HANDLE processHandle = null;
        WinNT.HANDLE threadHandle = null;
        boolean assigned = false;
        try {
            job = JobApi.INSTANCE.CreateJobObjectW(null, null);
            if (isInvalid(job)) return failClosed(null, "Job Object 创建失败");
            WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
            startup.cb = new WinDef.DWORD(startup.size());
            WinBase.PROCESS_INFORMATION information = new WinBase.PROCESS_INFORMATION();
            char[] commandLine = (commandLine(command) + "\0").toCharArray();
            if (!JobApi.INSTANCE.CreateProcessW(null, commandLine, null, null, false,
                    new WinDef.DWORD(JobApi.CREATE_SUSPENDED), null, workingDirectory.toString(), startup, information)) {
                return Result.rejected("子进程启动失败");
            }
            processHandle = information.hProcess;
            threadHandle = information.hThread;
            long pid = information.dwProcessId.longValue();
            if (!JobApi.INSTANCE.AssignProcessToJobObject(job, processHandle)) {
                int error = Native.getLastError();
                terminateOwnProcess(processHandle);
                close(threadHandle);
                return Result.blocked(error == ERROR_ACCESS_DENIED
                        ? "Job Object 分配被拒绝，可能存在未验证的嵌套 Job"
                        : "Job Object 分配失败");
            }
            assigned = true;
            if (JobApi.INSTANCE.ResumeThread(threadHandle) == -1) {
                JobApi.INSTANCE.TerminateJobObject(job, 0x524239);
                return Result.blocked("子进程恢复失败");
            }
            int waitMillis = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, timeout.toMillis()));
            boolean completed = Kernel32.INSTANCE.WaitForSingleObject(processHandle, waitMillis) != WAIT_TIMEOUT;
            boolean timedOut = !completed;
            if (timedOut && !JobApi.INSTANCE.TerminateJobObject(job, 0x524239)) {
                return Result.blocked("超时后 Job Object 终止失败");
            }
            if (!completed && Kernel32.INSTANCE.WaitForSingleObject(processHandle, 5_000) == WAIT_TIMEOUT) {
                return Result.blocked("Job Object 终止后子进程仍未退出");
            }
            var exitCode = new com.sun.jna.ptr.IntByReference();
            if (!Kernel32.INSTANCE.GetExitCodeProcess(processHandle, exitCode)) {
                return Result.blocked("子进程退出码读取失败");
            }
            close(job);
            job = null;
            return new Result(timedOut ? "TIMED_OUT_TERMINATED" : "EXITED",
                    exitCode.getValue(), pid, assigned, true, outputFile);
        } catch (RuntimeException exception) {
            if (job != null && assigned) JobApi.INSTANCE.TerminateJobObject(job, 0x524239);
            return Result.blocked("Windows 进程监督调用失败");
        } finally {
            close(threadHandle);
            close(processHandle);
            close(job);
        }
    }

    private static Result failClosed(WinNT.HANDLE process, String reason) {
        terminateOwnProcess(process);
        return Result.blocked(reason);
    }

    private static void terminateOwnProcess(WinNT.HANDLE process) {
        if (!isInvalid(process)) Kernel32.INSTANCE.TerminateProcess(process, 0x524239);
    }

    private static String commandLine(List<String> command) {
        return command.stream().map(WindowsJobObjectSupervisor::quoteArgument)
                .reduce((left, right) -> left + " " + right).orElseThrow();
    }

    private static String quoteArgument(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        int slashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                slashes++;
            } else if (character == '"') {
                quoted.append("\\".repeat(slashes * 2 + 1)).append('"');
                slashes = 0;
            } else {
                if (slashes > 0) quoted.append("\\".repeat(slashes));
                slashes = 0;
                quoted.append(character);
            }
        }
        if (slashes > 0) quoted.append("\\".repeat(slashes * 2));
        return quoted.append('"').toString();
    }


    private static void close(WinNT.HANDLE handle) {
        if (!isInvalid(handle)) Kernel32.INSTANCE.CloseHandle(handle);
    }

    private static boolean isInvalid(WinNT.HANDLE handle) {
        return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == 0L;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    record Result(String state, int exitCode, long pid, boolean assignedToJob,
                  boolean jobTerminationObserved, Path outputFile) {
        static Result rejected(String reason) {
            return new Result("REJECTED_" + reason, -1, -1, false, false, null);
        }

        static Result blocked(String reason) {
            return new Result("BLOCKED_" + reason, -1, -1, false, false, null);
        }
    }

    private interface JobApi extends StdCallLibrary {
        JobApi INSTANCE = Native.load("kernel32", JobApi.class, W32APIOptions.UNICODE_OPTIONS);

        int CREATE_SUSPENDED = 0x00000004;

        boolean CreateProcessW(String applicationName, char[] commandLine,
                               WinBase.SECURITY_ATTRIBUTES processAttributes,
                               WinBase.SECURITY_ATTRIBUTES threadAttributes,
                               boolean inheritHandles, WinDef.DWORD creationFlags,
                               Pointer environment, String currentDirectory,
                               WinBase.STARTUPINFO startupInfo,
                               WinBase.PROCESS_INFORMATION processInformation);

        WinNT.HANDLE CreateJobObjectW(Pointer securityAttributes, String name);

        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

        boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);

        int ResumeThread(WinNT.HANDLE thread);
    }

}
