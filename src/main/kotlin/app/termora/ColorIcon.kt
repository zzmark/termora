package app.termora

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon

class ColorIcon(
    private val width: Int = 16,
    private val height: Int = 16,
    private val color: Color,
    private val circle: Boolean = true,
    private val borderColor: Color? = null,
) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        if (g is Graphics2D) {
            g.save()
            setupAntialiasing(g)
            g.color = color
            if (circle) {
                g.fillRoundRect(x, y, width, width, width, width)
            } else {
                g.fillRect(x, y, iconWidth, iconHeight)
            }
            if (borderColor != null) {
                g.color = borderColor
                g.stroke = BasicStroke(1f)
                if (circle) {
                    g.drawRoundRect(x, y, width - 1, width - 1, width - 1, width - 1)
                } else {
                    g.drawRect(x, y, iconWidth - 1, iconHeight - 1)
                }
            }
            g.restore()
        }
    }

    override fun getIconWidth(): Int {
        return width
    }

    override fun getIconHeight(): Int {
        return height
    }
}
