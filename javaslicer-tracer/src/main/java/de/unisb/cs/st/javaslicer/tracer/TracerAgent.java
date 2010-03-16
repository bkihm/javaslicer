package de.unisb.cs.st.javaslicer.tracer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.instrument.Instrumentation;

import de.unisb.cs.st.javaslicer.common.exceptions.TracerException;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequenceFactory;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.gzip.GZipTraceSequenceFactory;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.sequitur.SequiturTraceSequenceFactory;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.uncompressed.UncompressedTraceSequenceFactory;

public class TracerAgent {

    public static class WriteTracefileThread extends UntracedThread {

        private final Tracer tracer;

        public WriteTracefileThread(final Tracer tracer) {
            super("tracing finisher");
            this.tracer = tracer;
        }

        @Override
        public void run() {
            try {
                this.tracer.finish();
            } catch (final IOException e) {
                this.tracer.error(e);
            }
            this.tracer.printFinalUserInfo();
        }

    }

    public static void premain(final String agentArgs, final Instrumentation inst) {
        try {
            String logFilename = null;
            final String[] args = agentArgs == null || agentArgs.length() == 0 ? new String[0] : agentArgs.split(",");

            boolean debug = false;
            boolean check = false;
            TraceSequenceFactory seqFac = null;

            for (final String arg : args) {
                final int colonPos = arg.indexOf(':');
                final String key = colonPos == -1 ? arg : arg.substring(0, colonPos);
                final String value = colonPos == -1 ? null : arg.substring(colonPos+1);

                if ("help".equalsIgnoreCase(key) || "h".equalsIgnoreCase(key)) {
                	String format = "%20s     %s%n";
                	System.out.println("Use the java agent this way: java -javaagent:tracer.jar=<agentoption>,<agentoption>,... -jar program.jar <programoptions>");
                	System.out.println("These are the available agent options:");
                	System.out.format(format, "check", "(true/false): do check the instrumented bytecode using ASM validators");
                	System.out.format(format, "compression", "(none/gzip/sequitur): select the compression algorithm for the trace file");
                	System.out.format(format, "debug", "(true/false): do additional checks and verbose output");
                	System.out.format(format, "help", "print this help");
                	System.out.format(format, "tracefile", "the output destination for the trace file");
                } else if ("logfile".equalsIgnoreCase(key) || "tracefile".equalsIgnoreCase(key)) {
                    if (value == null) {
                        System.err.println("ERROR: expecting value for \"logfile\" argument");
                        System.exit(1);
                    }
                    logFilename = value;
                } else if ("debug".equalsIgnoreCase(key)) {
                    if (value == null || "true".equalsIgnoreCase(value)) {
                        debug = true;
                    } else if ("false".equalsIgnoreCase(value)) {
                        debug = false;
                    } else {
                        System.err.println("ERROR: illegal value for \"debug\" argument: \"" + value + "\"");
                        System.exit(1);
                    }
                } else if ("check".equalsIgnoreCase(key)) {
                    if (value == null || "true".equalsIgnoreCase(value)) {
                        check = true;
                    } else if ("false".equalsIgnoreCase(value)) {
                        check = false;
                    } else {
                        System.err.println("ERROR: illegal value for \"check\" argument: \"" + value + "\"");
                        System.exit(1);
                    }
                } else if ("compression".equalsIgnoreCase(key)) {
                    if ("none".equalsIgnoreCase(value) || "uncompressed".equalsIgnoreCase(value)) {
                        seqFac = new UncompressedTraceSequenceFactory();
                    } else if ("gzip".equalsIgnoreCase(value)) {
                        seqFac = new GZipTraceSequenceFactory();
                    } else if ("sequitur".equalsIgnoreCase(value)) {
                        seqFac = new SequiturTraceSequenceFactory();
                    } else {
                        System.err.println("Unknown compression method: " + value);
                        System.exit(1);
                    }
                } else {
                    System.err.println("Unknown argument: " + key);
                    System.exit(1);
                }
            }

            if (logFilename == null) {
                System.err.println("ERROR: no logfile specified");
                System.exit(1);
            }

            final File logFile = new File(logFilename);
            if (logFile.exists() && !logFile.canWrite()) {
                System.err.println("ERROR: Cannot write logfile \"" + logFile.getAbsolutePath() + "\"");
                System.exit(1);
            }

            if (seqFac == null)
                seqFac = new UncompressedTraceSequenceFactory();

            try {
                Tracer.newInstance(logFile, debug, check, seqFac, inst);
            } catch (final FileNotFoundException e) {
                System.err.println("ERROR: cannot create trace file: " + e.getMessage());
            }
            final Tracer tracer = Tracer.getInstance();
            try {
                tracer.add(inst, true);
            } catch (final TracerException e) {
                System.err.println("ERROR: could not add instrumenting agent:");
                e.printStackTrace(System.err);
                System.exit(1);
            }
            final ThreadTracer tt = tracer.getThreadTracer();
            tt.pauseTracing();
            Runtime.getRuntime().addShutdownHook(new WriteTracefileThread(tracer));
            tt.resumeTracing();
        } catch (final Throwable t) {
            System.err.println("ERROR in premain method:");
            t.printStackTrace(System.err);
        }
    }

}
