package org.mapfish.print.processor.jasper;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;

import jsr166y.ForkJoinPool;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.data.JRTableModelDataSource;
import org.mapfish.print.Constants;
import org.mapfish.print.attribute.LegendAttribute.LegendAttributeValue;
import org.mapfish.print.config.Configuration;
import org.mapfish.print.config.Template;
import org.mapfish.print.http.MfClientHttpRequestFactory;
import org.mapfish.print.processor.AbstractProcessor;
import org.mapfish.print.processor.InternalValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.annotation.Resource;
import javax.imageio.ImageIO;

/**
 * Create a legend.
 *
 * @author Jesse
 * @author sbrunner
 * @author Vincent D. (galigeo)
 * @author Ganaël J. (galigeo)
 */
public final class LegendProcessor extends AbstractProcessor<LegendProcessor.Input, LegendProcessor.Output> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegendProcessor.class);
    private static final String NAME_COLUMN = "name";
    private static final String ICON_COLUMN = "icon";
    private static final String REPORT_COLUMN = "report";
    private static final String LEVEL_COLUMN = "level"; 
    private static final String VALUE_COLUMN = "value"; // @author Galigeo // Column for handling value
    @Autowired
    private JasperReportBuilder jasperReportBuilder;

    @Autowired
    private MetricRegistry metricRegistry;
    
    @Resource(name = "requestForkJoinPool")
    private ForkJoinPool requestForkJoinPool;

    // CSOFF:MagicNumber
    private Dimension missingImageSize = new Dimension(24, 24);
    // CSON:MagicNumber
    private BufferedImage missingImage;
    private Color missingImageColor = Color.PINK;
    private String template;
    private Integer maxWidth = null;
    private Double dpi = Constants.PDF_DPI;

    /**
     * Constructor.
     */
    protected LegendProcessor() {
        super(Output.class);
    }

    /**
     * The path to the Jasper Report template for rendering the legend data.
     *
     * @param template path to the template file
     */
    public void setTemplate(final String template) {
        this.template = template;
    }

    /**
     * The maximum width in pixels for the legend graphics.
     * If this parameter is set, the legend graphics are cropped to the given maximum
     * width. In this case a sub-report is created containing the graphic.
     * For reference see the example `legend_dynamic`.
     *
     * @param maxWidth The max. width.
     */
    public void setMaxWidth(final Integer maxWidth) {
        this.maxWidth = maxWidth;
    }

    /**
     * The DPI value that is used for the legend graphics.
     * Note: This parameter is only considered when `maxWidth` is set.
     *
     * @param dpi The DPI value.
     */
    public void setDpi(final Double dpi) {
        this.dpi = dpi;
    }

    @Override
    public Input createInputParameter() {
        return new Input();
    }

    @Override
    public Output execute(final Input values, final ExecutionContext context) throws Exception {
        final List<Object[]> legendList = new ArrayList<Object[]>();
        final String[] legendColumns = {NAME_COLUMN, ICON_COLUMN, VALUE_COLUMN, REPORT_COLUMN, LEVEL_COLUMN}; // @author Galigeo // Add the value column
        final LegendAttributeValue legendAttributes = values.legend;
        fillLegend(values.clientHttpRequestFactory, legendAttributes, legendList, 0, context, values.tempTaskDirectory);
        final Object[][] legend = new Object[legendList.size()][];

        final JRTableModelDataSource dataSource = new JRTableModelDataSource(new TableDataSource(legendColumns,
                legendList.toArray(legend)));

        String compiledTemplatePath = compileTemplate(values.template.getConfiguration());

        return new Output(dataSource, legendList.size(), compiledTemplatePath);
    }

    private String compileTemplate(final Configuration configuration) throws JRException {
        if (this.template != null) {
            final File file = new File(configuration.getDirectory(), this.template);
            return this.jasperReportBuilder.compileJasperReport(configuration, file).getAbsolutePath();
        }
        return null;
    }
    
    private class IconTask implements Callable<Object[]> {

        private URL icon;
        private ExecutionContext context;
        private MfClientHttpRequestFactory clientHttpRequestFactory;
        private int level;
        private File tempTaskDirectory;
        
        public IconTask(final URL icon, final ExecutionContext context, 
                final int level, final File tempTaskDirectory,
                final MfClientHttpRequestFactory clientHttpRequestFactory) {
            this.icon = icon;
            this.context = context;
            this.level = level;
            this.clientHttpRequestFactory = clientHttpRequestFactory;
            this.tempTaskDirectory = tempTaskDirectory;
        }

        @Override
        public Object[] call() throws IOException, URISyntaxException, JRException {
            BufferedImage image = null;
            final URI uri = this.icon.toURI();
            final String metricName = LegendProcessor.class.getName() + ".read." + uri.getHost();
            try {
                checkCancelState(this.context);
                final ClientHttpRequest request = this.clientHttpRequestFactory.createRequest(uri, HttpMethod.GET);
                final Timer.Context timer = LegendProcessor.this.metricRegistry.timer(metricName).time();
                final ClientHttpResponse httpResponse = request.execute();
                try {
                    if (httpResponse.getStatusCode() == HttpStatus.OK) {
                        image = ImageIO.read(httpResponse.getBody());
                        if (image == null) {
                            LOGGER.warn("The URL: " + this.icon + " is NOT an image format that can be decoded");
                        } else {
                            timer.stop();
                        }
                    } else {
                        LOGGER.warn("Failed to load image from: " + this.icon
                                + " due to server side error.\n\tResponse Code: " + httpResponse.getStatusCode()
                                + "\n\tResponse Text: " + httpResponse.getStatusText());
                    }
                } finally {
                    httpResponse.close();
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load image from: " + this.icon, e);
            }

            if (image == null) {
                image = getMissingImage();
                LegendProcessor.this.metricRegistry.counter(metricName + ".error").inc();
            }

            String report = null;
            if (LegendProcessor.this.maxWidth != null) {
                // if a max width is given, create a sub-report containing the cropped graphic
                report = createSubReport(image, this.tempTaskDirectory).toString();
            }
            return new Object[] {null, image, report, this.level};
        }
    }

    private void fillLegend(final MfClientHttpRequestFactory clientHttpRequestFactory,
                            final LegendAttributeValue legendAttributes,
                            final List<Object[]> legendList,
                            final int level,
                            final ExecutionContext context,
                            final File tempTaskDirectory) throws IOException, URISyntaxException, JRException {
        int insertNameIndex = legendList.size();
        final URL[] icons = legendAttributes.icons;
        Closer closer = Closer.create();
        
        
        if (icons != null) {
            for (URL icon : icons) {
                BufferedImage image = null;
                try {
                    checkCancelState(context);
                    final ClientHttpRequest request = clientHttpRequestFactory.createRequest(icon.toURI(), HttpMethod.GET);
                    final ClientHttpResponse httpResponse = closer.register(request.execute());
                    if (httpResponse.getStatusCode() == HttpStatus.OK) {
                        image = ImageIO.read(httpResponse.getBody());
                        if (image == null) {
                            LOGGER.warn("The URL: " + icon + " is NOT an image format that can be decoded");
                        }
                    } else {
                        LOGGER.warn("Failed to load image from: " + icon + " due to server side error.\n\tResponse Code: " +
                                    httpResponse.getStatusCode() + "\n\tResponse Text: " + httpResponse.getStatusText());
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load image from: " + icon, e);
                } finally {
                    closer.close();
                }

                if (image == null) {
                    image = this.getMissingImage();
                }

                String report = null;
                if (this.maxWidth != null) {
                    // if a max width is given, create a sub-report containing the cropped graphic
                    report = createSubReport(image, tempTaskDirectory).toString();
                }
                final Object[] iconRow;
                // @author Galigeo 
                // Check if a value is associated with the icons in the object and add it to the object if true
                if (legendAttributes.value != null) {
                	iconRow = new Object[]{null, image, legendAttributes.value, report, level};
                } else {
                	iconRow = new Object[]{null, image, null, report, level};
                }
                
				legendList.add(iconRow);
            }
        }

        if (legendAttributes.classes != null) {
            for (LegendAttributeValue value : legendAttributes.classes) {
                fillLegend(clientHttpRequestFactory, value, legendList, level + 1, context, tempTaskDirectory);
            }
        }

        if (!legendList.isEmpty()) {
            legendList.add(insertNameIndex, new Object[]{legendAttributes.name, null, null, null, level});
        }
        
        //if (!legendList.isEmpty()) {
       //     legendList.add(new Object[]{null, null, legendAttributes.value, null, level});
        //}
    }
    
    private class NameTask implements Callable<Object[]> {
        
        private String name;
        private int level;
        
        public NameTask(final String name, final int level) {
            this.name = name;
            this.level = level;
        }

        @Override
        public Object[] call() {
            return new Object[]{this.name, null, null, this.level};
        }
    }
    
    private void createTasks(final MfClientHttpRequestFactory clientHttpRequestFactory,
                            final LegendAttributeValue legendAttributes,
                            final ExecutionContext context, final File tempTaskDirectory,
                            final int level, final List<Callable<Object[]>> tasks) {
        int insertNameIndex = tasks.size();        
        final URL[] icons = legendAttributes.icons;
        if (icons != null && icons.length > 0) {
            for (URL icon : icons) {
                tasks.add(new IconTask(icon, context, level, tempTaskDirectory, clientHttpRequestFactory));
            }
        }
        if (legendAttributes.classes != null) {
            for (LegendAttributeValue value : legendAttributes.classes) {
                createTasks(clientHttpRequestFactory, value, context, tempTaskDirectory, level + 1, tasks);
            }
        }

        if (!tasks.isEmpty()) {
            tasks.add(insertNameIndex, new NameTask(legendAttributes.name, level));
        }
    }
        
    private void fillLegend(final MfClientHttpRequestFactory clientHttpRequestFactory, // not used I think because we overrided it already
                            final LegendAttributeValue legendAttributes,
                            final List<Object[]> legendList,
                            final ExecutionContext context,
                            final File tempTaskDirectory) throws ExecutionException, JRException, InterruptedException, IOException {
        List<Callable<Object[]>> tasks = new ArrayList<Callable<Object[]>>();
        createTasks(clientHttpRequestFactory, legendAttributes, context, tempTaskDirectory, 0, tasks);
        List<Future<Object[]>> futures = this.requestForkJoinPool.invokeAll(tasks);            


        for (Future<Object[]> future : futures) {
           legendList.add(future.get());
        }

    }

    private URI createSubReport(final BufferedImage originalImage,
                                final File tempTaskDirectory) throws IOException, JRException {
        assert (this.maxWidth != null);

        double scaleFactor = getScaleFactor();
        BufferedImage image = originalImage;
        if (image.getWidth() * scaleFactor > this.maxWidth) {
            image = cropToMaxWidth(image, scaleFactor);
        }

        URI imageFile = writeToFile(image, tempTaskDirectory);

        final ImagesSubReport subReport = new ImagesSubReport(
                Lists.newArrayList(imageFile),
                new Dimension((int) (image.getWidth() * scaleFactor), (int) (image.getHeight() * scaleFactor)),
                this.dpi);

        final File compiledReport = File.createTempFile("legend-report-",
                JasperReportBuilder.JASPER_REPORT_COMPILED_FILE_EXT, tempTaskDirectory);
        subReport.compile(compiledReport);

        return compiledReport.toURI();
    }

    private BufferedImage cropToMaxWidth(final BufferedImage image, final double scaleFactor) {
        int width = (int) Math.round(this.maxWidth / scaleFactor);
        return image.getSubimage(0, 0, width, image.getHeight());
    }

    private double getScaleFactor() {
        return Constants.PDF_DPI / this.dpi;
    }

    private URI writeToFile(final BufferedImage image, final File tempTaskDirectory) throws IOException {
        File path = File.createTempFile("legend-", ".png", tempTaskDirectory);
        ImageIO.write(image, "png", path);
        return path.toURI();
    }

    @Override
    protected void extraValidation(final List<Throwable> validationErrors, final Configuration configuration) {
        // no checks needed
    }

    private synchronized BufferedImage getMissingImage() {
        if (this.missingImage == null) {
            this.missingImage = new BufferedImage(this.missingImageSize.width, this.missingImageSize.height, BufferedImage.TYPE_INT_RGB);
            final Graphics2D graphics = this.missingImage.createGraphics();

            try {
                graphics.setBackground(this.missingImageColor);
                graphics.clearRect(0, 0, this.missingImageSize.width, this.missingImageSize.height);
            } finally {
                graphics.dispose();
            }
        }
        return this.missingImage;
    }

    /**
     * The Input Parameter object for {@link org.mapfish.print.processor.jasper.LegendProcessor}.
     */
    public static final class Input {
        /**
         * The template that contains this processor.
         */
        @InternalValue
        public Template template;
        /**
         * A factory for making http requests.  This is added to the values by the framework and therefore
         * does not need to be set in configuration
         */
        @InternalValue
        public MfClientHttpRequestFactory clientHttpRequestFactory;
        /**
         * The path to the temporary directory for the print task.
         */
        @InternalValue
        public File tempTaskDirectory;
        /**
         * The data required for creating the legend.
         */
        public LegendAttributeValue legend;
    }

    /**
     * The Output object of the legend processor method.
     */
    public static final class Output {
        /**
         * The datasource for the legend object in the report.
         */
        public final JRTableModelDataSource legend;
        /**
         * The path to the compiled subreport.
         */
        public final String legendSubReport;
        /**
         * The number of rows in the legend.
         */
        public final int numberOfLegendRows;

        Output(final JRTableModelDataSource legend, final int numberOfLegendRows, final String legendSubReport) {
            this.legend = legend;
            this.numberOfLegendRows = numberOfLegendRows;
            this.legendSubReport = legendSubReport;
        }
    }
}
