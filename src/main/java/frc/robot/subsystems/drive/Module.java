// Copyright 2021-2024 FRC 6328
// http://github.com/Mechanical-Advantage
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// version 3 as published by the Free Software Foundation or
// available in the root directory of this project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.

package frc.robot.subsystems.drive;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.util.Units;
import frc.robot.Constants;
import frc.robot.util.LoggedTunableNumber;
import org.littletonrobotics.junction.Logger;

public class Module {
  private static final LoggedTunableNumber WHEEL_RADIUS =
      new LoggedTunableNumber("Drive/WheelRadius");
  private static final LoggedTunableNumber DRIVE_KS = new LoggedTunableNumber("Drive/DriveKs");
  private static final LoggedTunableNumber DRIVE_KV = new LoggedTunableNumber("Drive/DriveKv");
  private static final LoggedTunableNumber DRIVE_KP = new LoggedTunableNumber("Drive/DriveKp");
  private static final LoggedTunableNumber DRIVE_KD = new LoggedTunableNumber("Drive/DriveKd");
  private static final LoggedTunableNumber TURN_KP = new LoggedTunableNumber("Drive/TurnKp");
  private static final LoggedTunableNumber TURN_KD = new LoggedTunableNumber("Drive/TurnKd");

  private final ModuleIO io;
  private final ModuleIOInputsAutoLogged inputs = new ModuleIOInputsAutoLogged();
  private final int index;

  private SimpleMotorFeedforward driveFeedforward;
  private final PIDController driveFeedback;
  private final PIDController turnFeedback;
  private Rotation2d angleSetpoint = null; // Setpoint for closed loop control, null for open loop
  private Double speedSetpoint = null; // Setpoint for closed loop control, null for open loop
  private Rotation2d turnRelativeOffset = null; // Relative + Offset = Absolute
  private double lastPositionMeters = 0.0; // Used for delta calculation

  static {
    // Switch constants based on mode (the physics simulator is treated as a
    // separate robot with different tuning)
    switch (Constants.currentMode) {
      case REAL:
      case REPLAY:
        WHEEL_RADIUS.initDefault(Units.inchesToMeters(1.5));
        DRIVE_KS.initDefault(0.0);
        DRIVE_KV.initDefault(0.0);
        DRIVE_KP.initDefault(0.08);
        DRIVE_KD.initDefault(0.0);
        TURN_KP.initDefault(9.0);
        TURN_KD.initDefault(0.0);
        break;
      case SIM:
        WHEEL_RADIUS.initDefault(Units.inchesToMeters(1.5));
        DRIVE_KS.initDefault(0.02284);
        DRIVE_KV.initDefault(0.10084);
        DRIVE_KP.initDefault(0.1);
        DRIVE_KD.initDefault(0.0);
        TURN_KP.initDefault(10.0);
        TURN_KD.initDefault(0.0);
        break;
      default:
        WHEEL_RADIUS.initDefault(Units.inchesToMeters(1.5));
        DRIVE_KS.initDefault(0.0);
        DRIVE_KV.initDefault(0.0);
        DRIVE_KP.initDefault(0.0);
        DRIVE_KD.initDefault(0.0);
        TURN_KP.initDefault(0.0);
        TURN_KD.initDefault(0.0);
        break;
    }
  }

  public Module(ModuleIO io, int index) {
    this.io = io;
    this.index = index;

    driveFeedforward = new SimpleMotorFeedforward(DRIVE_KS.get(), DRIVE_KV.get());
    driveFeedback =
        new PIDController(DRIVE_KP.get(), 0.0, DRIVE_KD.get(), Constants.LOOP_PERIOD_SECS);
    turnFeedback = new PIDController(TURN_KP.get(), 0.0, TURN_KD.get(), Constants.LOOP_PERIOD_SECS);

    turnFeedback.enableContinuousInput(-Math.PI, Math.PI);
    setBrakeMode(true);
  }

  public void periodic() {
    io.updateInputs(inputs);
    Logger.processInputs("Drive/Module" + Integer.toString(index), inputs);

    // Update from tunable numbers
    if (DRIVE_KP.hasChanged(hashCode()) || DRIVE_KD.hasChanged(hashCode())) {
      driveFeedback.setPID(DRIVE_KP.get(), 0.0, DRIVE_KD.get());
    }
    if (TURN_KP.hasChanged(hashCode()) || TURN_KD.hasChanged(hashCode())) {
      turnFeedback.setPID(TURN_KP.get(), 0.0, TURN_KD.get());
    }
    if (DRIVE_KS.hasChanged(hashCode()) || DRIVE_KV.hasChanged(hashCode())) {
      driveFeedforward = new SimpleMotorFeedforward(DRIVE_KS.get(), DRIVE_KV.get());
    }

    // On first cycle, reset relative turn encoder
    // Wait until absolute angle is nonzero in case it wasn't initialized yet
    if (turnRelativeOffset == null && inputs.turnAbsolutePosition.getRadians() != 0.0) {
      turnRelativeOffset = inputs.turnAbsolutePosition.minus(inputs.turnPosition);
    }

    // Run closed loop turn control
    if (angleSetpoint != null) {
      io.setTurnVoltage(
          turnFeedback.calculate(getAngle().getRadians(), angleSetpoint.getRadians()));

      // Run closed loop drive control
      // Only allowed if closed loop turn control is running
      if (speedSetpoint != null) {
        // Scale velocity based on turn error
        //
        // When the error is 90°, the velocity setpoint should be 0. As the wheel turns
        // towards the setpoint, its velocity should increase. This is achieved by
        // taking the component of the velocity in the direction of the setpoint.
        double adjustSpeedSetpoint = speedSetpoint * Math.cos(turnFeedback.getPositionError());

        // Run drive controller
        double velocityRadPerSec = adjustSpeedSetpoint / WHEEL_RADIUS.get();
        io.setDriveVoltage(
            driveFeedforward.calculate(velocityRadPerSec)
                + driveFeedback.calculate(inputs.driveVelocityRadPerSec, velocityRadPerSec));
      }
    }
  }

  /** Runs the module with the specified setpoint state. Returns the optimized state. */
  public SwerveModuleState runSetpoint(SwerveModuleState state) {
    // Optimize state based on current angle
    // Controllers run in "periodic" when the setpoint is not null
    var optimizedState = SwerveModuleState.optimize(state, getAngle());

    // Update setpoints, controllers run in "periodic"
    angleSetpoint = optimizedState.angle;
    speedSetpoint = optimizedState.speedMetersPerSecond;

    return optimizedState;
  }

  /** Runs the module with the specified voltage while controlling to zero degrees. */
  public void runCharacterization(double volts) {
    // Closed loop turn control
    angleSetpoint = new Rotation2d();

    // Open loop drive control
    io.setDriveVoltage(volts);
    speedSetpoint = null;
  }

  /** Disables all outputs to motors. */
  public void stop() {
    io.setTurnVoltage(0.0);
    io.setDriveVoltage(0.0);

    // Disable closed loop control for turn and drive
    angleSetpoint = null;
    speedSetpoint = null;
  }

  /** Sets whether brake mode is enabled. */
  public void setBrakeMode(boolean enabled) {
    io.setDriveBrakeMode(enabled);
    io.setTurnBrakeMode(enabled);
  }

  /** Returns the current turn angle of the module. */
  public Rotation2d getAngle() {
    if (turnRelativeOffset == null) {
      return new Rotation2d();
    } else {
      return inputs.turnPosition.plus(turnRelativeOffset);
    }
  }

  /** Returns the current drive position of the module in meters. */
  public double getPositionMeters() {
    return inputs.drivePositionRad * WHEEL_RADIUS.get();
  }

  /** Returns the current drive velocity of the module in meters per second. */
  public double getVelocityMetersPerSec() {
    return inputs.driveVelocityRadPerSec * WHEEL_RADIUS.get();
  }

  /** Returns the module position (turn angle and drive position). */
  public SwerveModulePosition getPosition() {
    return new SwerveModulePosition(getPositionMeters(), getAngle());
  }

  /** Returns the module position delta since the last call to this method. */
  public SwerveModulePosition getPositionDelta() {
    var delta = new SwerveModulePosition(getPositionMeters() - lastPositionMeters, getAngle());
    lastPositionMeters = getPositionMeters();
    return delta;
  }

  /** Returns the module state (turn angle and drive velocity). */
  public SwerveModuleState getState() {
    return new SwerveModuleState(getVelocityMetersPerSec(), getAngle());
  }

  /** Returns the drive velocity in radians/sec. */
  public double getCharacterizationVelocity() {
    return inputs.driveVelocityRadPerSec;
  }
}
