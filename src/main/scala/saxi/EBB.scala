package saxi

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets

import com.fazecast.jSerialComm
import com.fazecast.jSerialComm.SerialPort
import saxi.Planning.Plan

class OpenEBB(port: SerialPort) {
  // TODO: this doesn't handle the ebb's weird \n\r thing. Maybe use scanner instead?
  private val in = new BufferedReader(new InputStreamReader(port.getInputStream))

  private def readLine(): String = {
    val line = Iterator.continually(in.readLine().stripLineEnd).find(_.nonEmpty).get
    //println(s"[debug] read: $line")
    if (line.startsWith("!")) {
      throw new RuntimeException(s"EBB returned error: $line")
    }
    line
  }

  def query(cmd: String): String = {
    val bytes = s"$cmd\r".getBytes(StandardCharsets.US_ASCII)
    port.writeBytes(bytes, bytes.length)
    readLine()
  }

  def queryM(cmd: String): Seq[String] = {
    val bytes = s"$cmd\r".getBytes(StandardCharsets.US_ASCII)
    port.writeBytes(bytes, bytes.length)
    Iterator.continually(readLine()).takeWhile(_ != "OK").toList
  }

  def command(cmd: String): Unit = {
    println(s"[debug ${System.currentTimeMillis()}] sending: $cmd")
    val bytes = s"$cmd\r".getBytes(StandardCharsets.US_ASCII)
    port.writeBytes(bytes, bytes.length)
    val resp = readLine()
    if (!resp.startsWith("OK")) {
      throw new RuntimeException(
        s"Unexpected response from EBB:\nCommand: $cmd\nResponse: $resp")
    }
  }

  def enableMotors(microsteppingMode: Int): Unit = {
    require(
      1 <= microsteppingMode && microsteppingMode <= 5,
      s"Microstepping mode must be between 1 and 5, but was $microsteppingMode"
    )
    command(s"EM,$microsteppingMode,$microsteppingMode")
  }

  def disableMotors(): Unit = command("EM,0,0")

  def raisePen(duration: Int): Unit = {
    require(duration >= 0)
    command(s"SP,0,$duration")
  }

  def lowerPen(duration: Int): Unit = {
    require(duration >= 0)
    command(s"SP,1,$duration")
  }

  def lowlevelMove(
    stepsAxis1: Long,
    initialStepsPerSecAxis1: Double,
    finalStepsPerSecAxis1: Double,
    stepsAxis2: Long,
    initialStepsPerSecAxis2: Double,
    finalStepsPerSecAxis2: Double
  ): Unit = {
    val (initialRate1, deltaR1) = axisRate(stepsAxis1, initialStepsPerSecAxis1, finalStepsPerSecAxis1)
    val (initialRate2, deltaR2) = axisRate(stepsAxis2, initialStepsPerSecAxis2, finalStepsPerSecAxis2)
    command(s"LM,$initialRate1,$stepsAxis1,$deltaR1,$initialRate2,$stepsAxis2,$deltaR2")
  }

  // TODO: it seems like the math is off with a=x+y, b=x-y, but i think it's being accounted for in the stepsPerInch
  // constant?
  private val rotFactor: Double = 1//math.sin(math.Pi/4)

  /**
    * Use the low-level move command "LM" to perform a constant-acceleration stepper move.
    *
    * Available with EBB firmware 2.5.3 and higher.
    *
    * @param xSteps Number of steps to move in the X direction
    * @param ySteps Number of steps to move in the Y direction
    * @param initialRate Initial step rate, in steps per second
    * @param finalRate Final step rate, in steps per second
    */
  def moveWithAcceleration(xSteps: Long, ySteps: Long, initialRate: Double, finalRate: Double): Unit = {
    require(xSteps != 0 || ySteps != 0, "Must move on at least one axis")
    require(initialRate >= 0 && finalRate >= 0, "Rates must be positive")
    require(initialRate > 0 || finalRate > 0, "Must have non-zero velocity during motion")
    val stepsAxis1: Long = ((xSteps + ySteps) * rotFactor).round
    val stepsAxis2: Long = ((xSteps - ySteps) * rotFactor).round
    val norm = math.sqrt(math.pow(xSteps.toDouble, 2) + math.pow(ySteps, 2))
    val normX = xSteps / norm
    val normY = ySteps / norm
    val initialRateX = initialRate * normX
    val initialRateY = initialRate * normY
    val finalRateX = finalRate * normX
    val finalRateY = finalRate * normY
    val initialRateAxis1 = math.abs(initialRateX + initialRateY) * rotFactor
    val initialRateAxis2 = math.abs(initialRateX - initialRateY) * rotFactor
    val finalRateAxis1 = math.abs(finalRateX + finalRateY) * rotFactor
    val finalRateAxis2 = math.abs(finalRateX - finalRateY) * rotFactor
    lowlevelMove(stepsAxis1, initialRateAxis1, finalRateAxis1, stepsAxis2, initialRateAxis2, finalRateAxis2)
  }

  /**
    * Helper method for computing axis rates for the LM command.
    *
    * See http://evil-mad.github.io/EggBot/ebb.html#LM
    *
    * @param steps Number of steps being taken
    * @param initialStepsPerSec Initial movement rate, in steps per second
    * @param finalStepsPerSec Final movement rate, in steps per second
    * @return A tuple of (initialAxisRate, deltaR) that can be passed to the LM command
    */
  private def axisRate(steps: Long, initialStepsPerSec: Double, finalStepsPerSec: Double): (Long, Long) = {
    val initialRate: Long = (initialStepsPerSec * ((1L << 31)/25000f)).round
    val finalRate: Long = (finalStepsPerSec * ((1L << 31)/25000f)).round
    val moveTime = 2f * math.abs(steps) / (initialStepsPerSec + finalStepsPerSec)
    val deltaR: Long = ((finalRate - initialRate) / (moveTime * 25000f)).round
    (initialRate, deltaR)
  }

  /**
    * Use the high-level move command "XM" to perform a constant-velocity stepper move.
    *
    * @param duration Duration of the move, in seconds
    * @param x Number of steps to move in the X direction
    * @param y Number of steps to move in the Y direction
    */
  def moveAtConstantRate(duration: Double, x: Long, y: Long): Unit = {
    command(s"XM,${(duration * 1000).toLong},$x,$y")
  }

  private def modf(d: Double): (Double, Long) = {
    val intPart = d.toLong
    val fracPart = d - intPart
    (fracPart, intPart)
  }

  def executePlan(plan: Plan, stepsPerUnit: Double): Unit = {
    var error = Vec2(0, 0)
    for (block <- plan.blocks) {
      val (errX, stepsX) = modf((block.p2.x - block.p1.x) * stepsPerUnit + error.x)
      val (errY, stepsY) = modf((block.p2.y - block.p1.y) * stepsPerUnit + error.y)
      error = Vec2(errX, errY)
      if (stepsX != 0 || stepsY != 0) {
        moveWithAcceleration(
          stepsX,
          stepsY,
          block.vInitial * stepsPerUnit,
          (block.vInitial + block.accel * block.duration) * stepsPerUnit
        )
      }
    }
  }

  def executePlanWithoutLM(plan: Plan, stepsPerUnit: Double): Unit = {
    val timestepSec = 15 / 1000d
    var error = Vec2(0, 0)
    var t = 0d
    while (t < plan.tMax) {
      val i1 = plan.instant(t)
      val i2 = plan.instant(t + timestepSec)
      val d = i2.p - i1.p
      val (ex, sx) = modf(d.x * stepsPerUnit + error.x)
      val (ey, sy) = modf(d.y * stepsPerUnit + error.y)
      error = Vec2(ex, ey)
      moveAtConstantRate(timestepSec, sx, sy)
      t += timestepSec
    }
  }

  def waitUntilMotorsIdle(): Unit = {
    Iterator.continually { query("QM") }.find(_.split(",") match {
      case Array("QM", commandStatus, motor1Status, motor2Status, fifoStatus) =>
        commandStatus == "0" && fifoStatus == "0"
    })
  }

  /**
    * Query voltages for board & steppers. Useful to check whether stepper power is plugged in.
    *
    * @return Tuple of (RA0_VOLTAGE, V+_VOLTAGE, VIN_VOLTAGE)
    */
  def queryVoltages(): (Double, Double, Double) = {
    val Array(ra0Voltage, vPlusVoltage) = queryM("QC").head.split(",")
    (
      ra0Voltage.toInt / 1023.0 * 3.3,
      vPlusVoltage.toInt / 1023.0 * 3.3,
      vPlusVoltage.toInt / 1023.0 * 3.3 * 9.2 + 0.3
    )
  }

  def areSteppersPowered(): Boolean = {
    val (_, _, vInVoltage) = queryVoltages()
    vInVoltage > 6
  }

  /**
    * Query the firmware version running on the EBB.
    *
    * @return The version string, e.g. "Version: EBBv13_and_above EB Firmware Version 2.5.3"
    */
  def firmwareVersion(): String = query("V")

  /**
    * @return true iff the EBB firmware supports the LM command.
    */
  def supportsLM(): Boolean = {
    val Array(major, minor, patch) = firmwareVersion().split(" ").last.split("\\.").map(_.toInt)
    import scala.math.Ordering.Implicits._
    (major, minor, patch) >= (2, 5, 3)
  }
}

class EBB(port: SerialPort) {
  private def exhaust(): Unit = {
    while (true) {
      val buf = new Array[Byte](1)
      if (port.readBytes(buf, 1) != 1)
        return
    }
  }

  def open(f: OpenEBB => Unit): Unit = {
    if (!port.openPort()) {
      throw new RuntimeException(s"Couldn't open serial device ${port.getSystemPortName}")
    }
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING|SerialPort.TIMEOUT_WRITE_BLOCKING, 2500, 2500)
    exhaust()
    try {
      f(new OpenEBB(port))
    } finally {
      if (!port.closePort()) {
        throw new RuntimeException(s"Couldn't close serial device ${port.getSystemPortName}")
      }
    }
  }
}

object EBB {
  def findEiBotBoard(): Option[SerialPort] =
    jSerialComm.SerialPort.getCommPorts find { _.getDescriptivePortName startsWith "EiBotBoard" }

  def findFirst: EBB = findEiBotBoard() match {
    case Some(port) => new EBB(port)
    case None => throw new RuntimeException("Couldn't find a connected EiBotBoard")
  }
}
