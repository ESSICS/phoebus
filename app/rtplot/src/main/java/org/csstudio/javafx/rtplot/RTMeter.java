/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.csstudio.javafx.rtplot;

import static org.csstudio.javafx.rtplot.Activator.logger;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.IllegalPathStateException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.csstudio.javafx.rtplot.internal.AxisPart;
import org.csstudio.javafx.rtplot.internal.MeterScale;
import org.csstudio.javafx.rtplot.internal.PlotPart;
import org.csstudio.javafx.rtplot.internal.PlotPartListener;
import org.phoebus.ui.javafx.BufferUtil;
import org.phoebus.ui.javafx.DoubleBuffer;
import org.phoebus.ui.javafx.UpdateThrottle;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/** Meter with scale and needle
 *
 *  <p>A 'canvas' that draws its content in a background thread.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class RTMeter extends ImageView
{
    private static final int NEEDLE_BASE = 2*AxisPart.TICK_WIDTH;

    /** Background color */
    private volatile Color background = Color.WHITE,
                           needle = Color.RED;

    public static final String FONT_FAMILY = "Liberation Sans";

    /** Area of this plot */
    protected volatile Rectangle area = new Rectangle(0, 0, 0, 0);

    /** Listener to {@link PlotPart}s, triggering refresh of plot */
    protected final PlotPartListener plot_part_listener = new PlotPartListener()
    {
        @Override
        public void layoutPlotPart(final PlotPart plotPart)
        {
            need_layout.set(true);
        }

        @Override
        public void refreshPlotPart(final PlotPart plotPart)
        {
            if (! in_update)
                requestUpdate();
        }
    };

    private final MeterScale scale = new MeterScale("scale", plot_part_listener);

    private volatile double value = 0.0;

    /** Suppress updates triggered by axis changes from layout or autoscale
     *
     *  Calling updateImageBuffer can trigger axis changes because of layout
     *  or autoscale, which call the plot_part_listener.
     */
    private volatile boolean in_update = false;

    /** Does layout need to be re-computed? */
    protected final AtomicBoolean need_layout = new AtomicBoolean(true);

    /** Does plot image to be re-created? */
    protected final AtomicBoolean need_update = new AtomicBoolean(true);

    /** Throttle updates, enforcing a 'dormant' period */
    private final UpdateThrottle update_throttle;

    /** Buffer for image and color bar
     *
     *  <p>UpdateThrottle calls updateImageBuffer() to set the image
     *  in its thread, then redrawn in UI thread.
     */
    private volatile BufferedImage plot_image = null;

    /** Has a call to redraw_runnable already been queued?
     *  Cleared when redraw_runnable is executed
     */
    private final AtomicBoolean pending_redraw = new AtomicBoolean();

    private WritableImage awt_jfx_convert_buffer = null;



    /** Redraw the plot on UI thread by painting the 'plot_image' */
    private final Runnable redraw_runnable = () ->
    {
        // Indicate that a redraw has occurred
        pending_redraw.set(false);

        final BufferedImage copy = plot_image;
        if (copy != null)
        {
            // Create copy of basic plot
            if (copy.getType() != BufferedImage.TYPE_INT_ARGB)
                throw new IllegalPathStateException("Need TYPE_INT_ARGB for direct buffer access, not " + copy.getType());
            final int width = copy.getWidth(), height = copy.getHeight();
            final int[] src  = ((DataBufferInt)     copy.getRaster().getDataBuffer()).getData();

            // Convert to JFX image and show
            if (awt_jfx_convert_buffer == null  ||
                awt_jfx_convert_buffer.getWidth() != width ||
                awt_jfx_convert_buffer.getHeight() != height)
                awt_jfx_convert_buffer = new WritableImage(width, height);
            // SwingFXUtils.toFXImage(combined, image);
            awt_jfx_convert_buffer.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), src, 0, width);

            setImage(awt_jfx_convert_buffer);
        }
    };

    public RTMeter()
    {
        // 200ms = 5Hz default throttle
        update_throttle = new UpdateThrottle(200, TimeUnit.MILLISECONDS, () ->
        {
            if (need_update.getAndSet(false))
            {
                in_update = true;
                final BufferedImage latest = updateImageBuffer();
                in_update = false;
                if (latest == null)
                    // Update failed, request another
                    requestUpdate();
                else
                    plot_image = latest;
            }
            if (!pending_redraw.getAndSet(true))
                Platform.runLater(redraw_runnable);
        });
    }

    /** Call to update size of plot
     *
     *  @param width
     *  @param height
     */
    public void setSize(final double width, final double height)
    {
        area = new Rectangle((int)width, (int)height);
        need_layout.set(true);
        requestUpdate();
    }

    public void setValue(final double value)
    {
        this.value = value;
        requestUpdate();
    }

    /** Request a complete redraw of the plot with new layout */
    final public void requestLayout()
    {
        need_layout.set(true);
        need_update.set(true);
        update_throttle.trigger();
    }

    /** Request a complete update of plot image */
    final public void requestUpdate()
    {
        need_update.set(true);
        update_throttle.trigger();
    }

    private void computeLayout(final Graphics2D gc, final Rectangle bounds)
    {
        logger.log(Level.FINE, "computeLayout");

        // Needle origin
        int center_x = bounds.x + bounds.width/2;
        int center_y = bounds.height;

        // Start and range of scale
        int start_angle = 160;
        int angle_range = -140;

        // Radius (from origin) of scale
        int scale_rx = bounds.width /2 - AxisPart.TICK_WIDTH;
        int scale_ry = bounds.height - AxisPart.TICK_WIDTH;

        scale.setBounds(bounds);
        scale.configure(center_x, center_y, scale_rx, scale_ry, start_angle, angle_range);
    }

    /** Buffers used to create the next image buffer */
    private final DoubleBuffer buffers = new DoubleBuffer();

    /** Draw all components into image buffer
     *  @return Latest image, must be of type BufferedImage.TYPE_INT_ARGB
     */
    protected BufferedImage updateImageBuffer()
    {
        final Rectangle area_copy = area;
        if (area_copy.width <= 0  ||  area_copy.height <= 0)
            return null;

        final BufferUtil buffer = buffers.getBufferedImage(area_copy.width, area_copy.height);
        if (buffer == null)
            return null;
        final BufferedImage image = buffer.getImage();
        final Graphics2D gc = buffer.getGraphics();

        // Really need AA for text to avoid anemic fonts.
        // AA for lines results in some fuzzyness,
        // but also required for any line that's not strictly horizontal or vertical.
        gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Text AA is implied in general AA
        // gc.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        gc.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        gc.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        gc.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        if (need_layout.getAndSet(false))
            computeLayout(gc, area_copy);

        gc.setColor(background);
        gc.fillRect(0, 0, area_copy.width, area_copy.height);

        scale.paint(gc, area_copy);

        // Needle
        double angle = scale.getAngle(value);
        System.out.println("Value: " + value);
        System.out.println("Angle: " + angle);
        angle = Math.toRadians(angle);
        final Stroke orig_stroke = gc.getStroke();
        gc.setStroke(AxisPart.TICK_STROKE);

        int[] nx = new int[]
        {
            (int) (scale.getCenterX() + scale.getRadiusX() * Math.cos(angle) + 0.5),
            (int) (scale.getCenterX() + NEEDLE_BASE * Math.cos(angle + Math.PI/2) + 0.5),
            (int) (scale.getCenterX() + NEEDLE_BASE * Math.cos(angle - Math.PI/2) + 0.5),
        };
        int[] ny = new int[]
        {
            (int) (scale.getCenterY() - scale.getRadiusY() * Math.sin(angle) + 0.5),
            (int) (scale.getCenterY() - NEEDLE_BASE * Math.sin(angle + Math.PI/2) + 0.5),
            (int) (scale.getCenterY() - NEEDLE_BASE * Math.sin(angle - Math.PI/2) + 0.5),
        };
        gc.setColor(needle);
        gc.fillPolygon(nx, ny, 3);

        gc.setStroke(orig_stroke);

        return image;
    }

    /** Should be invoked when meter no longer used to release resources */
    public void dispose()
    {
        // Release memory ASAP
    }
}
