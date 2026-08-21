package emulator.ui

import emulator.input.CombinedGamepadInput
import emulator.input.CombinedGamepadState
import emulator.sim.Pose
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

private const val TICK_MS = 50
const val RUNNER_SHELL_TICK_SECONDS = TICK_MS / 1000.0

/**
 * A generic Init/Start/Stop desktop shell: field view, port monitor, telemetry, and gamepad
 * input, driven entirely through callbacks/suppliers so it has no dependency on what's actually
 * being simulated. Lives here (rather than in `TeamCode/src/test/.../emulator`, which is what
 * actually uses it) because Android Gradle Plugin's local-unit-test compile classpath has no
 * `java.awt`/`javax.swing` at all -- see that module's README for why.
 *
 * [onTick] is called once per timer tick, before the panels refresh; it's given the tick's
 * [CombinedGamepadState] (real controller if one's connected, keyboard otherwise -- see
 * [CombinedGamepadInput]) so callers can drive gamepad1/gamepad2 without reference to AWT or any
 * particular input device.
 */
class RunnerShellApp(
    title: String,
    opModeNames: List<String>,
    private val onInit: (selectedIndex: Int) -> Unit,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onResetField: () -> Unit,
    private val onTick: (dtSeconds: Double, gamepads: CombinedGamepadState) -> Unit,
    private val poseSupplier: () -> Pose,
    portRowsSupplier: () -> List<PortRowView>,
    telemetrySupplier: () -> List<String>,
    crashSupplier: () -> Throwable?,
    private val statusSupplier: () -> String,
    private val batteryVoltageSupplier: () -> Double,
    private val onClosed: () -> Unit,
    robotLengthIn: Double = 18.0,
    robotWidthIn: Double = 18.0
) : JFrame(title) {
    private val keyTracker = KeyTracker()
    private val gamepadInput = CombinedGamepadInput(keyTracker)

    private val fieldPanel = PoseFieldPanel(poseSupplier, robotLengthIn, robotWidthIn)
    private val portMonitorPanel = PortRowMonitorPanel(portRowsSupplier())
    private val telemetryPanel = SnapshotTelemetryPanel(telemetrySupplier, crashSupplier)

    private val opModeSelector = JComboBox(opModeNames.toTypedArray())
    private val initButton = JButton("Init")
    private val startStopButton = JButton("Start").apply { isEnabled = false }
    private val resetFieldButton = JButton("Reset Field")
    private val stateLabel = JLabel("State: STOPPED")
    private val batteryLabel = JLabel("Battery: -- V")
    private val gamepadSourceLabel = JLabel("Gamepad 1: Keyboard   Gamepad 2: None")
    private val tickTimer = Timer(TICK_MS) { tick() }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        layout = BorderLayout()

        add(buildControlBar(), BorderLayout.NORTH)

        val rightTabs = JTabbedPane().apply {
            addTab("Port Monitor", portMonitorPanel)
            addTab("Telemetry", telemetryPanel)
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fieldPanel, rightTabs).apply { resizeWeight = 0.55 }, BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)

        addKeyListener(keyTracker)
        isFocusable = true
        focusTraversalKeysEnabled = false

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                tickTimer.stop()
                onStop()
                dispose()
                onClosed()
            }
        })

        pack()
        setLocationRelativeTo(null)
        tickTimer.start()
        requestFocusInWindow()
    }

    private fun buildControlBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT))
        bar.add(JLabel("OpMode:"))
        bar.add(opModeSelector)
        bar.add(initButton)
        bar.add(startStopButton)
        bar.add(resetFieldButton)

        initButton.addActionListener {
            onInit(opModeSelector.selectedIndex)
            startStopButton.text = "Start"
            startStopButton.isEnabled = true
            requestFocusInWindow()
        }
        startStopButton.addActionListener {
            if (startStopButton.text == "Start") {
                onStart()
                startStopButton.text = "Stop"
            } else {
                onStop()
                startStopButton.text = "Start"
                startStopButton.isEnabled = false
            }
            requestFocusInWindow()
        }
        resetFieldButton.addActionListener {
            onResetField()
            fieldPanel.resetTrail()
            requestFocusInWindow()
        }
        return bar
    }

    private fun buildStatusBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT))
        bar.add(stateLabel)
        bar.add(batteryLabel)
        bar.add(gamepadSourceLabel)
        return bar
    }

    private fun tick() {
        val gamepads = gamepadInput.poll()
        onTick(RUNNER_SHELL_TICK_SECONDS, gamepads)
        fieldPanel.onTick()
        portMonitorPanel.onTick()
        telemetryPanel.onTick()
        stateLabel.text = statusSupplier()
        batteryLabel.text = "Battery: %.2f V".format(batteryVoltageSupplier())
        gamepadSourceLabel.text = "Gamepad 1: ${gamepads.gamepad1Source}   Gamepad 2: ${gamepads.gamepad2Source}"
    }
}

/**
 * Builds and shows a [RunnerShellApp] on the Swing event thread, blocking the calling thread
 * until the window is closed. Exists so callers that can't reference `javax.swing`/`java.awt`
 * themselves (see the class doc above) never have to.
 *
 * [robotLengthIn]/[robotWidthIn] only affect the field view's drawn robot box -- pass the same
 * values you gave your [emulator.sim.MecanumGeometry] if you customized it, so the drawing matches
 * where [emulator.sim.MecanumRobot] actually stops the robot.
 */
fun runRunnerShellAndBlock(
    title: String,
    opModeNames: List<String>,
    onInit: (selectedIndex: Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResetField: () -> Unit,
    onTick: (dtSeconds: Double, gamepads: CombinedGamepadState) -> Unit,
    poseSupplier: () -> Pose,
    portRowsSupplier: () -> List<PortRowView>,
    telemetrySupplier: () -> List<String>,
    crashSupplier: () -> Throwable?,
    statusSupplier: () -> String,
    batteryVoltageSupplier: () -> Double,
    robotLengthIn: Double = 18.0,
    robotWidthIn: Double = 18.0
) {
    val latch = CountDownLatch(1)
    SwingUtilities.invokeLater {
        RunnerShellApp(
            title, opModeNames, onInit, onStart, onStop, onResetField, onTick,
            poseSupplier, portRowsSupplier, telemetrySupplier, crashSupplier,
            statusSupplier, batteryVoltageSupplier,
            onClosed = { latch.countDown() },
            robotLengthIn = robotLengthIn,
            robotWidthIn = robotWidthIn
        ).isVisible = true
    }
    latch.await()
}
