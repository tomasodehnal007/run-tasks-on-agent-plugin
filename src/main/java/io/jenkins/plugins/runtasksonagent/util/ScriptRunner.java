package io.jenkins.plugins.runtasksonagent.util;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.util.LineEndingConversion;
import hudson.util.LineEndingConversion.EOLType;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;

/** Runs a shell script on the agent, and limits how long scripts may run. */
public final class ScriptRunner {

    /**
     * The longest time a script (shell or Groovy) may run; 60 minutes by default, changed by the system property
     * {@code io.jenkins.plugins.runtasksonagent.util.ScriptRunner.timeout} (in minutes, or with a unit such as
     * {@code 90m}). Without a limit a stuck script would hold the build, and its executor, forever.
     */
    static Duration timeout = SystemProperties.getDuration(
            ScriptRunner.class.getName() + ".timeout", ChronoUnit.MINUTES, Duration.ofMinutes(60));

    private ScriptRunner() {}

    /** Code run by {@link #withTimeout}. */
    @FunctionalInterface
    public interface Body<T> {
        T run() throws IOException, InterruptedException;
    }

    /**
     * Runs the code on the current thread and interrupts it if it does not finish within {@link #timeout}. An
     * interrupted script is stopped: a process started by a {@link Launcher} is killed when the thread waiting for it
     * is interrupted, and a call to the agent is cancelled there.
     *
     * @param <T> the result of the code
     * @param what what is run, for the message
     * @param body the code
     * @return the result of the code
     * @throws AbortException if the time ran out
     * @throws IOException if the code fails
     * @throws InterruptedException if the thread is interrupted for another reason (e.g. the build is aborted)
     */
    public static <T> T withTimeout(String what, Body<T> body) throws IOException, InterruptedException {
        Duration limit = timeout;
        Thread thread = Thread.currentThread();
        // Guarded by itself: the watchdog interrupts only while the body runs, so no interrupt leaks out of here.
        boolean[] state = new boolean[2]; // [0] the body has finished, [1] the watchdog has interrupted it
        ScheduledFuture<?> watchdog = Timer.get()
                .schedule(
                        () -> {
                            synchronized (state) {
                                if (!state[0]) {
                                    state[1] = true;
                                    thread.interrupt();
                                }
                            }
                        },
                        limit.toMillis(),
                        TimeUnit.MILLISECONDS);
        try {
            return body.run();
        } catch (InterruptedException e) {
            if (timedOut(state)) {
                throw new AbortException(what + " did not finish within " + describe(limit) + " and was stopped");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            synchronized (state) {
                state[0] = true;
            }
            if (state[1]) {
                Thread.interrupted(); // our own interrupt, if the body did not notice it
            }
        }
    }

    private static boolean timedOut(boolean[] state) {
        synchronized (state) {
            return state[1];
        }
    }

    private static String describe(Duration duration) {
        return duration.toMinutes() > 0 ? duration.toMinutes() + " minutes" : duration.toMillis() + " ms";
    }

    /**
     * Runs the script in a shell on the agent and writes its output into the build log.
     *
     * <p>The script runs in the build's workspace. Before the build (PRE_BUILD) Jenkins has not
     * allocated the workspace yet, so the script then runs in the agent's root directory instead.
     *
     * <p>Like the "Execute shell" and "Execute Windows batch command" build steps, the script is written
     * into a temporary file in the agent's temporary directory and run by {@code sh -xe} (or {@code cmd} on
     * Windows) in the working directory above, so pipes, {@code ;} and multi-line scripts work. On Unix, as in
     * "Execute shell", every command is printed before it runs and the script stops at the first failing
     * command. The file is deleted afterwards. A script still running after {@link #timeout} is killed.
     *
     * @param context the build and the agent to run the script on
     * @param script the text of the script
     * @return the exit code of the script
     * @throws AbortException if the agent has no directory to run the script in, or the script ran out of time
     * @throws IOException if the script file cannot be written or the process cannot be started
     * @throws InterruptedException if the thread is interrupted while waiting for the script
     */
    public static int run(TaskContext context, String script) throws IOException, InterruptedException {
        FilePath workingDirectory = context.workspace();
        if (workingDirectory == null) {
            Node node = context.computer().getNode();
            workingDirectory = node != null ? node.getRootPath() : null;
        }
        if (workingDirectory == null) {
            throw new AbortException("The agent has no directory to run the script in");
        }
        // On a brand-new agent the root directory is only created with the first workspace.
        workingDirectory.mkdirs();

        Launcher launcher = context.launcher();
        boolean unix = launcher.isUnix();
        FilePath file = unix
                ? workingDirectory.createTextTempFile(
                        "run-tasks-on-agent", ".sh", LineEndingConversion.convertEOL(script, EOLType.Unix), false)
                : workingDirectory.createTextTempFile(
                        "run-tasks-on-agent",
                        ".bat",
                        LineEndingConversion.convertEOL(script + "\r\nexit %ERRORLEVEL%", EOLType.Windows),
                        false);
        try {
            String[] command = unix
                    ? new String[] {"sh", "-xe", file.getRemote()}
                    : new String[] {"cmd", "/c", "call", file.getRemote()};
            FilePath pwd = workingDirectory;
            return withTimeout(
                    "The script",
                    () -> launcher.launch()
                            .cmds(command)
                            .quiet(true)
                            .stdout(context.listener())
                            .pwd(pwd)
                            .join());
        } finally {
            file.delete();
        }
    }
}
