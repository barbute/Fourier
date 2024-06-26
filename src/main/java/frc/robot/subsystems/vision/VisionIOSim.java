// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems.vision;

import edu.wpi.first.apriltag.AprilTagFields;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.filter.Debouncer;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import frc.robot.subsystems.vision.Vision.Camera;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.photonvision.EstimatedRobotPose;
import org.photonvision.PhotonCamera;
import org.photonvision.PhotonPoseEstimator;
import org.photonvision.PhotonPoseEstimator.PoseStrategy;
import org.photonvision.simulation.PhotonCameraSim;
import org.photonvision.simulation.SimCameraProperties;
import org.photonvision.simulation.VisionSystemSim;
import org.photonvision.targeting.PhotonPipelineResult;
import org.photonvision.targeting.PhotonTrackedTarget;

/** VisionIO implementation for using Photon Vision on a simulated camera */
public class VisionIOSim implements VisionIO {
  private PhotonCamera camera;
  private Transform3d cameraTransform;

  private PhotonPoseEstimator poseEstimator;
  private Matrix<N3, N1> singleTagStdDevs;
  private Matrix<N3, N1> multiTagStdDevs;

  private Debouncer debouncer;

  private int speakerTagID;

  private PhotonPipelineResult cameraResult = new PhotonPipelineResult();

  private PhotonCameraSim cameraSim;
  private VisionSystemSim visionSim;
  private Supplier<Pose2d> drivePose;

  public VisionIOSim(Camera polaroid, Supplier<Pose2d> drivePoseSupplier) {
    switch (polaroid) {
      case FRONT_LEFT_0:
        camera = new PhotonCamera("LLLeft");
        cameraTransform =
            new Transform3d(
                0.2,
                0.0,
                0.33,
                new Rotation3d(Math.toRadians(13.2), Math.toRadians(0.0), Math.toRadians(25.2)));
        break;
      case FRONT_RIGHT_1:
        camera = new PhotonCamera("LLRight");
        cameraTransform =
            new Transform3d(
                0.4,
                0.0,
                0.33,
                new Rotation3d(Math.toRadians(13.2), Math.toRadians(0.0), Math.toRadians(25.5)));
        break;
      default:
        break;
    }
    PhotonCamera.setVersionCheckEnabled(false);

    poseEstimator =
        new PhotonPoseEstimator(
            AprilTagFields.k2024Crescendo.loadAprilTagLayoutField(),
            PoseStrategy.MULTI_TAG_PNP_ON_COPROCESSOR,
            camera,
            cameraTransform);
    poseEstimator.setMultiTagFallbackStrategy(PoseStrategy.LOWEST_AMBIGUITY);

    singleTagStdDevs = VecBuilder.fill(0.0, 0.0, Double.MAX_VALUE);
    multiTagStdDevs = VecBuilder.fill(0.1, 0.1, Double.MAX_VALUE);

    speakerTagID =
        (DriverStation.getAlliance().isPresent()
                && DriverStation.getAlliance().get() == Alliance.Red)
            ? 3
            : 7;

    debouncer = new Debouncer(0.1);

    // Create the vision system simulation which handles cameras and targets on the field.
    visionSim = new VisionSystemSim("main");

    // Add all the AprilTags inside the tag layout as visible targets to this simulated field.
    visionSim.addAprilTags(AprilTagFields.k2024Crescendo.loadAprilTagLayoutField());

    // Create simulated camera properties. These can be set to mimic your actual camera.
    var cameraProp = new SimCameraProperties();
    cameraProp.setCalibration(960, 720, Rotation2d.fromDegrees(75));
    cameraProp.setCalibError(0.51, 0.28);
    cameraProp.setFPS(100.0);
    cameraProp.setAvgLatencyMs(90.0);
    cameraProp.setLatencyStdDevMs(15.0);

    // Create a PhotonCameraSim which will update the linked PhotonCamera's values with visible
    // targets.
    cameraSim = new PhotonCameraSim(camera, cameraProp);

    // Add the simulated camera to view the targets on this simulated field.
    visionSim.addCamera(cameraSim, cameraTransform);

    cameraSim.enableDrawWireframe(true);

    drivePose = drivePoseSupplier;
  }

  @Override
  public void updateInputs(VisionIOInputs inputs) {
    visionSim.update(drivePose.get());

    cameraResult = camera.getLatestResult();
    Optional<EstimatedRobotPose> estimatedRobotPose = poseEstimator.update(cameraResult);

    inputs.isConnected = camera.isConnected();
    inputs.hasTarget = cameraResult.hasTargets();

    if (inputs.hasTarget) {
      PhotonTrackedTarget target = cameraResult.getBestTarget();

      inputs.hasTargetDebounced = debouncer.calculate(inputs.hasTarget);
      inputs.hasSpeakerTarget = poseEstimator.getFieldTags().getTagPose(speakerTagID).isPresent();

      inputs.latencyS = cameraResult.getLatencyMillis() / 1000.0;
      inputs.numberOfTargets = getAprilTagCount(cameraResult);
      inputs.aprilTagID = target.getFiducialId();

      inputs.yaw = target.getYaw();
      inputs.pitch = target.getPitch();
      inputs.area = target.getArea();

      inputs.cameraToAprilTag = target.getBestCameraToTarget();
      inputs.robotToAprilTag = target.getBestCameraToTarget().plus(cameraTransform);
      inputs.speakerTagTransform = getSpeakerTagTransform(cameraResult, inputs);
      inputs.speakerTagPose =
          poseEstimator
              .getFieldTags()
              .getTagPose(speakerTagID)
              .orElse(new Pose3d())
              .plus(inputs.robotToAprilTag)
              .toPose2d();

      inputs.poseAmbiguity = target.getPoseAmbiguity();

      Matrix<N3, N1> speakerTagStdDevs = singleTagStdDevs;

      if (inputs.cameraToAprilTag.getTranslation().getNorm() > 5.0 && inputs.hasSpeakerTarget) {
        inputs.speakerXStdDev =
            speakerTagStdDevs.times(inputs.cameraToAprilTag.getTranslation().getNorm()).get(0, 0);
        inputs.speakerYStdDev =
            speakerTagStdDevs.times(inputs.cameraToAprilTag.getTranslation().getNorm()).get(1, 0);
        inputs.speakerThetaDev = Double.MAX_VALUE;
      } else {
        inputs.speakerXStdDev = Double.MAX_VALUE;
        inputs.speakerYStdDev = Double.MAX_VALUE;
        inputs.speakerThetaDev = Double.MAX_VALUE;
      }
    }

    inputs.latestTimestampS = cameraResult.getTimestampSeconds();

    estimatedRobotPose.ifPresent(
        est -> {
          inputs.estimatedRobotPose =
              estimatedRobotPose
                  .get()
                  .estimatedPose
                  .toPose2d()
                  .transformBy(
                      new Transform2d(new Translation2d(), Rotation2d.fromDegrees(-180.0)));

          Matrix<N3, N1> stdDevs = getEstimationStdDevs(inputs.estimatedRobotPose, cameraResult);

          inputs.xStdDev = stdDevs.get(0, 0);
          inputs.yStdDev = stdDevs.get(1, 0);
          inputs.thetaStdDev = stdDevs.get(2, 0);

          visionSim.getDebugField().setRobotPose(inputs.estimatedRobotPose);
        });
  }

  @Override
  public void setSingleTagStdDevs(double x, double y, double theta) {
    singleTagStdDevs = VecBuilder.fill(x, y, theta);
  }

  @Override
  public void setMultiTagStdDevs(double x, double y, double theta) {
    multiTagStdDevs = VecBuilder.fill(x, y, theta);
  }

  @Override
  public Matrix<N3, N1> getSingleTagStdDevsCoeff() {
    return singleTagStdDevs;
  }

  @Override
  public Matrix<N3, N1> getMultiTagStdDevsCoeff() {
    return multiTagStdDevs;
  }

  /**
   * Returns the number of April Tags currently being tracked, discards bad ones if ambiguity is too
   * high
   */
  private int getAprilTagCount(PhotonPipelineResult result) {
    List<PhotonTrackedTarget> targets = result.getTargets();
    int numTags = 0;

    for (PhotonTrackedTarget target : targets) {
      Optional<Pose3d> tagPose = poseEstimator.getFieldTags().getTagPose(target.getFiducialId());

      if (tagPose.isEmpty()) {
        continue;
      } else if (target.getPoseAmbiguity() > 0.45) {
        continue;
      }

      // Increase number of tags
      numTags++;
    }

    return numTags;
  }

  /** Returns the transform of the camera to the speaker tag */
  private Transform2d getSpeakerTagTransform(PhotonPipelineResult result, VisionIOInputs inputs) {
    boolean hasSpeakerTag = false;
    for (int i = 0; i < result.getTargets().size(); i++) {
      if (result.getTargets().get(i).getFiducialId() != 7) {
        continue;
      }
      Transform3d transform3d =
          result
              .getTargets()
              .get(i)
              .getBestCameraToTarget()
              .plus(cameraTransform.inverse())
              .plus(new Transform3d(0.0, 0.0, 0.0, new Rotation3d()));
      transform3d =
          transform3d.plus(new Transform3d(0.0, transform3d.getX() / 2.0, 0.0, new Rotation3d()));

      hasSpeakerTag = true;

      return new Transform2d(transform3d.getX(), transform3d.getY(), new Rotation2d());
    }
    inputs.hasSpeakerTarget = hasSpeakerTag;

    return new Transform2d();
  }

  /** Returns the estimated standard deviations of the camera */
  private Matrix<N3, N1> getEstimationStdDevs(Pose2d estimatedPose, PhotonPipelineResult result) {
    List<PhotonTrackedTarget> targets = result.getTargets();
    Matrix<N3, N1> estStdDevs = singleTagStdDevs;

    int numTags = 0;
    double avgDist = 0.0;

    for (PhotonTrackedTarget target : targets) {
      Optional<Pose3d> tagPose = poseEstimator.getFieldTags().getTagPose(target.getFiducialId());

      if (tagPose.isEmpty()) continue;
      // else if (target.getPoseAmbiguity() > 0.45) continue;

      // Increase number of tags
      numTags++;
      avgDist +=
          tagPose.get().toPose2d().getTranslation().getDistance(estimatedPose.getTranslation());
    }

    // No tags visible
    if (numTags == 0) {
      return estStdDevs;
    }

    // Calculate average distance
    avgDist /= numTags;

    // Decrease std devs if multiple targets are visible
    if (numTags > 1) {
      estStdDevs = multiTagStdDevs;
    }

    // Increase std devs based on (average) distance
    if (numTags == 1 && avgDist > 3.0) {
      estStdDevs = VecBuilder.fill(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
    } else {
      estStdDevs = estStdDevs.times((1.0 + (avgDist * avgDist / 30.0)) / (double) numTags);
    }

    return estStdDevs;
  }
}
