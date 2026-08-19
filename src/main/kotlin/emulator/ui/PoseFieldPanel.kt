package emulator.ui

import emulator.sim.Pose
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.JPanel

/**
 * Top-down view of a standard 144in x 144in FTC field, origin at center, +x right, +y up, reading
 * the robot's pose from a caller-supplied [poseSupplier] instead of owning any simulation state
 * itself -- used by [RunnerShellApp] so this same view works for any pose source.
 */
class PoseFieldPanel(private val poseSupplier: () -> Pose) : JPanel() {
    private val fieldSizeIn = 144.0
    private val robotSizeIn = 18.0
    private val trail = ArrayDeque<Pose>()
    private val maxTrailPoints = 400

    init {
        background = Color(24, 26, 30)
        preferredSize = Dimension(520, 520)
    }

    fun onTick() {
        val pose = poseSupplier()
        if (trail.isEmpty() || trail.last().let { (it.x - pose.x) * (it.x - pose.x) + (it.y - pose.y) * (it.y - pose.y) > 0.25 }) {
            trail.addLast(pose)
            while (trail.size > maxTrailPoints) trail.removeFirst()
        }
        repaint()
    }

    fun resetTrail() = trail.clear()

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val scale = minOf(width, height) / fieldSizeIn
        val toScreen = AffineTransform().apply {
            translate(width / 2.0, height / 2.0)
            scale(scale, -scale)
        }

        drawGrid(g2, toScreen)
        drawTrail(g2, toScreen)
        drawRobot(g2, toScreen)
    }

    private fun drawGrid(g2: Graphics2D, toScreen: AffineTransform) {
        g2.color = Color(45, 48, 54)
        g2.stroke = BasicStroke(1f)
        var i = -fieldSizeIn / 2
        while (i <= fieldSizeIn / 2) {
            g2.draw(Line2D.Double(toScreen.transform(Point2D.Double(i, -fieldSizeIn / 2), null), toScreen.transform(Point2D.Double(i, fieldSizeIn / 2), null)))
            g2.draw(Line2D.Double(toScreen.transform(Point2D.Double(-fieldSizeIn / 2, i), null), toScreen.transform(Point2D.Double(fieldSizeIn / 2, i), null)))
            i += 24.0
        }
        g2.color = Color(80, 84, 92)
        g2.draw(Line2D.Double(toScreen.transform(Point2D.Double(-fieldSizeIn / 2, 0.0), null), toScreen.transform(Point2D.Double(fieldSizeIn / 2, 0.0), null)))
        g2.draw(Line2D.Double(toScreen.transform(Point2D.Double(0.0, -fieldSizeIn / 2), null), toScreen.transform(Point2D.Double(0.0, fieldSizeIn / 2), null)))
    }

    private fun drawTrail(g2: Graphics2D, toScreen: AffineTransform) {
        if (trail.size < 2) return
        g2.color = Color(90, 170, 255, 140)
        g2.stroke = BasicStroke(2f)
        val points = trail.map { toScreen.transform(Point2D.Double(it.x, it.y), null) }
        for (i in 1 until points.size) g2.draw(Line2D.Double(points[i - 1], points[i]))
    }

    private fun drawRobot(g2: Graphics2D, toScreen: AffineTransform) {
        val pose = poseSupplier()
        val transform = AffineTransform(toScreen)
        transform.translate(pose.x, pose.y)
        transform.rotate(pose.headingRad)

        val body = transform.createTransformedShape(Rectangle2D.Double(-robotSizeIn / 2, -robotSizeIn / 2, robotSizeIn, robotSizeIn))
        g2.color = Color(60, 130, 220)
        g2.fill(body)
        g2.color = Color(150, 200, 255)
        g2.stroke = BasicStroke(2f)
        g2.draw(body)

        g2.color = Color(255, 200, 60)
        g2.draw(
            Line2D.Double(
                transform.transform(Point2D.Double(0.0, 0.0), null),
                transform.transform(Point2D.Double(robotSizeIn / 2 + 6, 0.0), null)
            )
        )
    }
}
