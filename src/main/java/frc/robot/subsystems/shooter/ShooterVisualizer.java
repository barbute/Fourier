// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems.shooter;

import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.wpilibj.smartdashboard.Mechanism2d;
import edu.wpi.first.wpilibj.smartdashboard.MechanismLigament2d;
import edu.wpi.first.wpilibj.smartdashboard.MechanismRoot2d;
import org.littletonrobotics.junction.Logger;

/** Class to publish data to network tables to visualize the shooter */
public class ShooterVisualizer {
  private final String ANGLER_LOG_KEY = "Shooter/Visualizer/Angler";

  private Mechanism2d anglerMechanismVisual = new Mechanism2d(1.0, 1.0);
  private MechanismRoot2d anglerPivotVisual =
      anglerMechanismVisual.getRoot("ANGLER_PIVOT", 0.5, 0.0);
  private MechanismLigament2d shooterVisual;

  /** Create a new shooter visualizer */
  public ShooterVisualizer(Rotation2d initialPosition) {
    shooterVisual =
        anglerPivotVisual.append(
            new MechanismLigament2d("SHOOTER", 0.4958, initialPosition.getRadians()));

    Logger.recordOutput(ANGLER_LOG_KEY, anglerMechanismVisual);
  }

  /** Updates the shooter visualier */
  public void updateShooterAngle(Rotation2d angle) {
    if (angle == null) {
      shooterVisual.setAngle(Rotation2d.fromDegrees(25.0));
    } else {
      shooterVisual.setAngle(angle.minus(Rotation2d.fromDegrees(180.0)).times(-1.0));
    }

    Logger.recordOutput(ANGLER_LOG_KEY, anglerMechanismVisual);
    Logger.recordOutput(
        ANGLER_LOG_KEY + "3D",
        new Pose3d(0.105, 0.0, 0.232, new Rotation3d(0.0, angle.getRadians(), 0.0)));
  }
}
