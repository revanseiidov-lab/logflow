package edu.logflow;

/** Giriş noktası. Ayrıntılar için bkz. {@link CliOptions#USAGE}. */
public final class Main {

    public static void main(String[] args) {
        System.exit(App.run(args, System.out, System.err));
    }
}
