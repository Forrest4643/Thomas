// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot;

import java.util.Calendar;
import edu.wpi.first.wpilibj.TimedRobot;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import com.revrobotics.CANEncoder;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import edu.wpi.first.wpilibj.SpeedControllerGroup;
import edu.wpi.first.wpilibj.XboxController;
import edu.wpi.first.wpilibj.buttons.JoystickButton;
import edu.wpi.first.wpilibj.drive.DifferentialDrive;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.SerialPort;
import com.kauailabs.navx.frc.AHRS;

/**
 * The VM is configured to automatically run this class, and to call the
 * functions corresponding to each mode, as described in the TimedRobot
 * documentation. If you change the name of this class or the package after
 * creating this project, you must also update the build.gradle file in the
 * project
 */
public class Robot extends TimedRobot {

  private Command m_autonomousCommand;

  private RobotContainer m_robotContainer;

  // com port 1 = xbox controller
  private final XboxController controller = new XboxController(1); 

  // defining motor can ids
  private final CANSparkMax leftFront = new CANSparkMax(1, MotorType.kBrushless);
  private final CANSparkMax leftRear = new CANSparkMax(2, MotorType.kBrushless);
  private final CANSparkMax rightRear = new CANSparkMax(3, MotorType.kBrushless);
  private final CANSparkMax rightFront = new CANSparkMax(4, MotorType.kBrushless);

  private CANEncoder leftDriveEncoder, rightDriveEncoder;

  // setting speed controller groups
  private final SpeedControllerGroup leftDrive = new SpeedControllerGroup(leftFront, leftRear);
  private final SpeedControllerGroup rightDrive = new SpeedControllerGroup(rightFront, rightRear);

  public final DifferentialDrive robotDrive = new DifferentialDrive(leftDrive, rightDrive);

  // defining leftbumper
  public final JoystickButton leftBumper = new JoystickButton(controller, 5);

  public final double stickDB = 0.04;

  // constants for drivetrain PID
  public final double drive_kP = 0.01;
  public final double drive_kI = 0.0072;
  public final double drive_kD = 0.000;
  public final double drive_error = .9;

  // for hooking
  public final double hook_multiplier = 0.5;

  // drive stick divider
  public final double contDiv = 1.25;

  // for teleop drive straight func
  double m_integral, m_derivative, m_headingAngle, m_prevError, m_leftDriveSpeed, m_rightDriveSpeed;
  boolean m_timerStarted, m_driveStraightInit;
  long m_startTime;

  // defining navx
  AHRS ahrs;

  public void straightCheese(double throttle, double steering, boolean quickTurn, double gyro) {

    boolean isThrottle = throttle < -stickDB || throttle > stickDB;

    boolean isTurning = steering < -stickDB || steering > stickDB;

    boolean sticksCentered = isThrottle && !isTurning;

    // hookDelay is the delay before the robot stores an angle and maintains said
    // angle.
    // This math sets the hookdelay to be proportional to the difference in speed
    // between the left and right motors.
    // This should help compensate for the time it takes the robot to stop turning.
    double hookDelay = Math.abs(m_leftDriveSpeed - m_leftDriveSpeed) * hook_multiplier;

    // calendar instance for timer
    Calendar calendar = Calendar.getInstance();

    // starts a timer
    if (sticksCentered && !m_timerStarted) {
      m_startTime = calendar.getTimeInMillis();
      m_timerStarted = true;
    }

    // compares current vs. time started
    long timer = calendar.getTimeInMillis() - m_startTime;

    SmartDashboard.putNumber("time", calendar.getTimeInMillis());
    SmartDashboard.putNumber("startTime", m_startTime);
    SmartDashboard.putNumber("timer", timer);

    boolean drivingStraight = sticksCentered && (timer > hookDelay);

    // after delay period, start drive straight
    if (drivingStraight) {
      if (!m_driveStraightInit) { // stores heading angle & resets integral and derivative
        m_headingAngle = gyro;
        m_derivative = 0;
        m_integral = 0;
      }
      // varibles for PID
      double error = m_headingAngle - gyro;
      m_integral = +(error * 0.2);

      // resets integral when target angle is reached so prolonged iteration doesn't
      // cause the system to overshoot
      if (error < m_headingAngle + drive_error || error > m_headingAngle - drive_error) {
        m_integral = 0;
      }

      // calculation of PID
      double steerAssist = (drive_kP * error) + (drive_kI * m_integral) + (drive_kD * m_derivative);

      // variables for PID
      m_prevError = m_headingAngle - gyro;
      m_derivative = (error - m_prevError) / 0.2;

      robotDrive.curvatureDrive(throttle, steerAssist, false);

    } else {
      // axis 1 = left stick y, axis 4 = right stick x
      robotDrive.curvatureDrive(throttle, steering / contDiv, quickTurn);

    }

    m_driveStraightInit = drivingStraight; // restore prev state

    m_timerStarted = sticksCentered; // resets timer when sticks straightened

    // printing variables to smartdashboard for troubleshooting
    SmartDashboard.putNumber("RS_X", controller.getRawAxis(4));
    SmartDashboard.putNumber("LS_Y", controller.getRawAxis(1));
    SmartDashboard.putNumber("headingangle", m_headingAngle);
    SmartDashboard.putNumber("gyro_x", gyro);
    SmartDashboard.putNumber("hookDelay", hookDelay);
  }

  /**
   * This function is run when the robot is first started up and should be used
   * for any initialization code.
   */
  @Override
  public void robotInit() {
    // Instantiate our RobotContainer. This will perform all our button bindings,
    // and put our
    // autonomous chooser on the dashboard.

    try {
      /* Communicate w/navX-MXP via the MXP SPI Bus. */
      /* Alternatively: I2C.Port.kMXP, SerialPort.Port.kMXP or SerialPort.Port.kUSB */
      /*
       * See http://navx-mxp.kauailabs.com/guidance/selecting-an-interface/ for
       * details.
       */
      ahrs = new AHRS(SerialPort.Port.kUSB);
    } catch (RuntimeException ex) {
      DriverStation.reportError("Error instantiating navX-MXP:  " + ex.getMessage(), true);
    }

    leftFront.setInverted(false);
    leftRear.setInverted(false);
    rightFront.setInverted(false);
    rightRear.setInverted(false);

    leftDrive.setInverted(false);
    rightDrive.setInverted(false);

    leftDriveEncoder = leftFront.getEncoder();
    rightDriveEncoder = rightFront.getEncoder();

    m_robotContainer = new RobotContainer();
  }

  /**
   * This function is called every robot packet, no matter the mode. Use this for
   * items like diagnostics that you want ran during disabled, autonomous,
   * teleoperated and test.
   *
   * <p>
   * This runs after the mode specific periodic functions, but before LiveWindow
   * and SmartDashboard integrated updating.
   */
  @Override
  public void robotPeriodic() {
    // Runs the Scheduler. This is responsible for polling buttons, adding
    // newly-scheduled
    // commands, running already-scheduled commands, removing finished or
    // interrupted commands,
    // and running subsystem periodic() methods. This must be called from the
    // robot's periodic
    // block in order for anything in the Command-based framework to work.

    m_leftDriveSpeed = leftDriveEncoder.getVelocity();
    m_rightDriveSpeed = rightDriveEncoder.getVelocity();

    CommandScheduler.getInstance().run();
  }

  /** This function is called once each time the robot enters Disabled mode. */
  @Override
  public void disabledInit() {
  }

  @Override
  public void disabledPeriodic() {
  }

  /**
   * This autonomous runs the autonomous command selected by your
   * {@link RobotContainer} class.
   */
  @Override
  public void autonomousInit() {
    m_autonomousCommand = m_robotContainer.getAutonomousCommand();

    // schedule the autonomous command (example)
    if (m_autonomousCommand != null) {
      m_autonomousCommand.schedule();
    }
  }

  /** This function is called periodically during autonomous. */
  @Override
  public void autonomousPeriodic() {
  }

  @Override
  public void teleopInit() {
    // This makes sure that the autonomous stops running when
    // teleop starts running. If you want the autonomous to
    // continue until interrupted by another command, remove
    // this line or comment it out.
    if (m_autonomousCommand != null) {
      m_autonomousCommand.cancel();

    }

  }

  /** This function is called periodically during operator control. */
  @Override
  public void teleopPeriodic() {

    straightCheese(controller.getRawAxis(1), controller.getRawAxis(4), leftBumper.get(), ahrs.getYaw());

  }

  @Override
  public void testInit() {
    // Cancels all running commands at the start of test mode.
    CommandScheduler.getInstance().cancelAll();
  }

  /** This function is called periodically during test mode. */
  @Override
  public void testPeriodic() {

  }
}
