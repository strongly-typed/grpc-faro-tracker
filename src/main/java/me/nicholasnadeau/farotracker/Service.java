package me.nicholasnadeau.farotracker;


import com.google.protobuf.BoolValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import me.nicholasnadeau.farotracker.FaroTrackerServiceGrpc.FaroTrackerServiceImplBase;
import smx.tracker.TrackerException;
import smx.tracker.NoTargetException;

import java.lang.Math;
import java.io.Closeable;
import java.io.IOException;
import java.util.logging.Logger;

class Service extends FaroTrackerServiceImplBase implements Runnable, Closeable {
    private static final Logger LOGGER = Logger.getLogger(Service.class.getName());
    private static final int DEFAULT_PORT = 30000;
    private FaroTracker faroTracker;
    private Server server;

    Service() throws TrackerException {
        this(new FaroTracker(), DEFAULT_PORT);
    }

    private Service(FaroTracker faroTracker, int port) {
        this.faroTracker = faroTracker;
        server = ServerBuilder.forPort(port).addService(this).build();
    }

    public void close() {
        LOGGER.info("Closing FARO service");
        this.server.shutdown();
        try {
            this.faroTracker.disconnect();
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
        }
    }


    void blockUntilShutdown() throws InterruptedException {
        LOGGER.info("Blocking until shutdown");
        this.server.awaitTermination();
    }

    @Override
    public void run() {
        try {
            startGrpcServer();
        } catch (IOException e) {
            LOGGER.severe(e.getLocalizedMessage());
        }

        try {
            connectFaro();
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }
    }

    private void connectFaro() throws TrackerException {
        LOGGER.info("Connecting to FARO");
        this.faroTracker.setBlocking(true);
        this.faroTracker.connect();
    }

    private void startGrpcServer() throws IOException {
        LOGGER.info("Starting gRPC server");
        this.server.start();
        LOGGER.info("Server started, listening on " + this.server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may has been reset by its
            // JVM shutdown hook.
            System.err.println("Shutting down gRPC server since JVM is shutting down");
            Service.this.close();
            System.err.println("Server shut down");
        }));
    }


    @Override
    public void moveCartesian(CartesianPosition request, StreamObserver<Empty> responseObserver) {
        LOGGER.info("Move Cartesian to:\n" + request);
        try {
            this.faroTracker.moveCartesian(new double[]{request.getX(), request.getY(), request.getZ()});
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void moveSpherical(SphericalPosition request, StreamObserver<Empty> responseObserver) {
        LOGGER.info("Move Spherical to:\n" + request);
        try {
            this.faroTracker.moveSpherical(new double[]{request.getAzimuth(), request.getZenith(), request.getDistance()});
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void moveHome(Empty request, StreamObserver<Empty> responseObserver) {
        LOGGER.info("Moving home");
        try {
            this.faroTracker.home();
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void initialize(Empty request, StreamObserver<Empty> responseObserver) {
        LOGGER.info("Initializing FARO");
        try {
            if (this.faroTracker.isInitialized()) {
                LOGGER.info("Tracker already initialized");
            } else {
                this.faroTracker.initialize();
                LOGGER.info("Setting home");
                this.faroTracker.home();
            }
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void isTargetDetected(Empty request, StreamObserver<BoolValue> responseObserver) {
        boolean targetDetected = false;

        for (int i = 0; i < 2; i++) {
            try {
                targetDetected = this.faroTracker.isTargetDetected();
                if (targetDetected) {
                    break;
                }
            } catch (TrackerException e) {
                LOGGER.severe(e.getText());
                this.close();
            }
        }

        LOGGER.info("Target detected: " + targetDetected);
        responseObserver.onNext(BoolValue.newBuilder().setValue(targetDetected).build());
        responseObserver.onCompleted();
    }

    @Override
    public void search(DoubleValue request, StreamObserver<BoolValue> responseObserver) {
        boolean targetDetected = false;
        LOGGER.info("Searching for target in radius: " + request.getValue());
        try {
            this.faroTracker.search(request.getValue());
            targetDetected = this.faroTracker.isTargetDetected();
        } catch (NoTargetException e) {
            LOGGER.info("No target found");
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }

        LOGGER.info("Target found: " + targetDetected);
        responseObserver.onNext(BoolValue.newBuilder().setValue(targetDetected).build());
        responseObserver.onCompleted();
    }

    @Override
    public void measurePoint(Empty request, StreamObserver<Measure> responseObserver) {
        boolean success = false;
        Measure measure = Measure.getDefaultInstance();
        try {
            // get position
            double[] doubles = this.faroTracker.measurePoint();
            doubles = this.faroTracker.sphericalToCartesian(doubles);
            CartesianPosition.Builder cartesianBuilder = CartesianPosition.newBuilder();
            cartesianBuilder
                    .setX(doubles[0])
                    .setY(doubles[1])
                    .setZ(doubles[2]);

            if ((Math.abs(doubles[0]) > 0.001) && (Math.abs(doubles[1]) > 0.001) && (Math.abs(doubles[2]) > 0.001)) {
                success = true;
            }

            // get temperature
            double temperature = this.faroTracker.getExtTemperature();

            // build message
            measure = Measure.newBuilder()
                    .setPosition(cartesianBuilder.build())
                    .setRms(0.0)
                    .setTemperature(temperature)
                    .setIsSuccess(success)
                    .build();
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }

        responseObserver.onNext(measure);
        responseObserver.onCompleted();
    }

    @Override
    public void measureLevel(Empty request, StreamObserver<Level> responseObserver) {
        double level[] = {0.0, 0.0, 0.0};
        LOGGER.info("Measuring level");
        try {
            level = this.faroTracker.measureLevel();
        } catch (TrackerException e) {
            LOGGER.severe(e.getText());
            this.close();
        }
        LOGGER.info("Level measured: " + level[0] + " " + level[1] + " " + level[2]);

        responseObserver.onNext(Level.newBuilder()
                .setRx(level[0])
                .setRy(level[1])
                .setRz(level[2])
                .setIsSuccess(true)
                .build()
        );
        responseObserver.onCompleted();
    }
}
