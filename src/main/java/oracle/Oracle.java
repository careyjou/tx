package oracle;
import oracle.bband.*;
import java.text.*;
import oracle.common.*;
import java.util.*;
import java.io.*;
import org.jfree.ui.*;
import org.jfree.chart.*;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.data.xy.XYSeries;
import org.jfree.chart.axis.*;
import org.jfree.chart.plot.*;
import java.awt.BasicStroke;

public class Oracle {
    private SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss");
    private BBandBuilder bbandBuilder = new BBandBuilder(ConfigurableParameters.KBAR_LENGTH/1000, 2);
    private Vector<Transaction> transactions = new Vector<Transaction>();
    private int duration = ConfigurableParameters.KBAR_LENGTH;
    private int tolerance = ConfigurableParameters.LOST_TOLERANCE;
    private int lifecycle = ConfigurableParameters.TRANS_LIFECYCLE;
    private int minimalBoundSize = ConfigurableParameters.BBAND_BOUND_SIZE;
    private KBarBuilder kbarBuilder = new KBarBuilder(duration); // in millisecond
    private Vector<Transaction> allTransactions = new Vector<Transaction>();
    private Date lastDate;
    
    public void streamingInput(String time, String value) {
        // build kbar unit
        // String[] input = line.split("\\s");
        // if(input.length != 3) {
        //     for(String s : input) {
        //         System.out.println(s);
        //     }
        //     throw new RuntimeException("Error input for building K bar...");
        // }
        kbarBuilder.append(time, value);
        KBarUnit kbarResult = null;
        if(lastDate == null) {
            try {
                lastDate = formatter.parse(time);
                return;
            }
            catch(ParseException e) {
                e.printStackTrace();
            }
        }
        else {
            Date now = null;
            try {
                now = formatter.parse(time);
            }
            catch(ParseException e) {
                e.printStackTrace();
            }
            if(now.getTime() - lastDate.getTime() >= ConfigurableParameters.KBAR_LENGTH) {
                kbarResult = kbarBuilder.consumeAndMakeKBar();
                lastDate = now;
            }
        }

        if(kbarResult != null) {
            String startDateStr = formatter.format(kbarResult.startDate);
            String endDateStr = formatter.format(kbarResult.endDate);
            String kbarResultStr = startDateStr + " " + endDateStr + " " +
                kbarResult.start + " " + kbarResult.high + " " + kbarResult.low + " " + kbarResult.end;

            // build bband and predict the future
            if(kbarResultStr.startsWith("#") || kbarResultStr.trim().equals("")) {
                return; // remark
            }
            else {
                String[] input = kbarResultStr.split("\\s");
                if(input.length != 6) {
                    for(String s : input) {
                        System.out.println(s);
                    }
                    throw new RuntimeException("Error input for building bband...");
                }
                bbandBuilder.parseOneK(kbarResultStr);


                // System.out.println(kbarResultStr + " :Guess=" + prediction);
                // int prediction = outOfBoundStrategy();
                int prediction = outOfBoundStrategy();

                if(prediction != 0) {
                    if(transactions.size() < ConfigurableParameters.MAX_CONCURRENT_TRANSACTION) {
                        BBandUnit lastBBandUnit = bbandBuilder.getLastBBandUnit();
                        Transaction trans = new Transaction(lastBBandUnit.end, lastBBandUnit.dateEnd, lifecycle, prediction, tolerance);
                        allTransactions.add(trans);
                        transactions.add(trans);
                    }
                    else {
                        // System.out.println("# Maximum number of concurrent transactions has reached.");
                    }
                }
            }
        }
    }

    private int kbarChasingStrategy() {
        BBandUnit lastBBandUnit = bbandBuilder.getLastBBandUnit();
        if(lastBBandUnit == null || lastBBandUnit.getBoundSize() < minimalBoundSize) {
            return 0;
        }
        else {
            int trend = bbandBuilder.getLatestTrend();
            if(trend == 1) {
                return 1;
            }
            else if(trend == -1) {
                return -1;
            }
            else {
                return 0;
            }
        }
    }

    private int MATrendStrategy() {
        BBandUnit lastBBandUnit = bbandBuilder.getLastBBandUnit();
        if(lastBBandUnit == null || lastBBandUnit.getBoundSize() < minimalBoundSize) {
            return 0;
        }
        else {
            int trend = bbandBuilder.getLatestTrend();
            if(trend == 1) {
                return 1;
            }
            else if(trend == -1) {
                return -1;
            }
            else {
                return 0;
            }
        }
    }

    private int percentBBStrategy() {
        BBandUnit lastBBandUnit = bbandBuilder.getLastBBandUnit();
        if(lastBBandUnit == null || lastBBandUnit.getBoundSize() < minimalBoundSize) {
            return 0;
        }
        else {
            double pBB = lastBBandUnit.getPercentBB();
            if(pBB == Double.MAX_VALUE || pBB == Double.MIN_VALUE) {
                return 0;
            }
            int prediction = 0;
            if(pBB > ConfigurableParameters.PBB_UPPER) {
                prediction = -1;
            }
            else if(pBB < ConfigurableParameters.PBB_LOWER) {
                prediction = 1;
            }
            return prediction;
        }
    }


    private int outOfBoundStrategy() {
        BBandUnit lastBBandUnit = bbandBuilder.getLastBBandUnit();
        int prediction = 0;
        if(lastBBandUnit != null && lastBBandUnit.getBoundSize() >= minimalBoundSize) {
            prediction = -1 * lastBBandUnit.isOutOfBound();
        }
        return prediction;
    }


    private int profit0 = 0;
    private int profit1 = 0;
    private int profit2 = 0;
    private int profit4 = 0;
    public void decideOffsetting(String newestTime, String newestValueStr) {
        double newestValue = Double.parseDouble(newestValueStr);
        Date newestDate = null;
        try {
            newestDate = formatter.parse(newestTime);
        }
        catch(ParseException e) {
            e.printStackTrace();
        }
        Vector<Transaction> transToRemove = new Vector<Transaction>();
        for(Transaction trans : transactions) {
            if(newestDate.getTime() - trans.birthday.getTime() >= trans.lifecycle) {
                // Date oneMinuteLater = new Date(trans.birthday.getTime() + trans.lifecycle);
                profit0 += trans.offset(newestValue, newestDate);
                transToRemove.add(trans);
                System.out.println("Offsetted transaction: " + trans);
                System.out.println("Profit 0 = " + profit0);
            }
            else if( (newestValue-trans.price)*trans.prediction <= -tolerance) {
                profit1 += trans.offset(newestValue, newestDate);
                System.out.println("Offsetted transaction: " + trans);
                System.out.println("Profit 1 = " + profit1);
                transToRemove.add(trans);
            }
            else if( bbandBuilder.getLatestTrend() * trans.prediction == -1 ) {
                trans.b2bWrongPrediction++;
                if(trans.b2bWrongPrediction >= ConfigurableParameters.MAX_B2B_WRONG_PREDICTION) {
                    profit2 += trans.offset(newestValue, newestDate);
                    System.out.println("Offsetted transaction: " + trans);
                    System.out.println("Profit 2 = " + profit2);
                    transToRemove.add(trans);
                    // trans.b2bWrongPrediction = 0;
                }
            }
            else {
                // still earning within 1 min
                // reset wrong prediction
                trans.b2bWrongPrediction = 0;
                BBandUnit lastBBandUnit = bbandBuilder.getLastBBandUnit();
                // offset if touched boundaries
                if(trans.prediction == 1) {
                    if(newestValue >= lastBBandUnit.upperBound) {
                        profit4 += trans.offset(newestValue, newestDate);
                        System.out.println("Offsetted transaction: " + trans);
                        System.out.println("Profit 4 = " + profit4);
                        transToRemove.add(trans);
                    }
                }
                else if(trans.prediction == -1) {
                    if(newestValue <= lastBBandUnit.lowerBound) {
                        profit4 += trans.offset(newestValue, newestDate);
                        System.out.println("Offsetted transaction: " + trans);
                        System.out.println("Profit 4 = " + profit4);
                        transToRemove.add(trans);
                    }
                }
                // still earning inside bband
            }
            // System.out.println(profit);
        }
        transactions.removeAll(transToRemove);
    }

    private int profit3 = 0;
    public void finishRemaining() {
        if(bbandBuilder.isEmpty()) {
            return;
        }
        else {
            BBandUnit lastBBandUnit = bbandBuilder.getLastBBandUnit();
            if(lastBBandUnit != null) {
                double newestPrice = lastBBandUnit.end;
                Date newestDate = lastBBandUnit.dateEnd;
                for(Transaction trans : transactions) {
                    profit3 += trans.offset(newestPrice, newestDate);
                }
            }
        }
    }

    public void logfileTest(String... args) {
        // input = k bar result
        BufferedReader reader = null;
        DataBroadcaster broadcaster = DataBroadcaster.getInstance();
        try {
            String line;
            reader = new BufferedReader(new FileReader(args[0]));
            // System.out.println("Input...");
            while((line=reader.readLine()) != null) {
                if(line.startsWith("#") || line.trim().equals("")) {
                    continue;
                }
                String[] input = line.split("\\s");
                if(input.length < 3) {
                    for(String s : input) {
                        System.out.println(s);
                    }
                    throw new RuntimeException("Error input for building K bar...");
                }
                streamingInput(input[1], input[2]);
                decideOffsetting(input[1], input[2]);
                // System.out.println(line);
            }
            reader.close();
        }
        catch(IOException e) {
            e.printStackTrace();
        }
        // System.out.println(bbandBuilder);
    }

    public String toString() {
        String ret = "# Transactions:\n";
        for(Transaction trans : allTransactions) {
            String line = trans.toString();
            if(line.equals("")) {
                continue;
            }
            else {
                ret += line + "\n";
            }
        }
        ret += "# Total number of transactions = " + allTransactions.size() + "\n";
        ret += "# Profit 0: Transaction timeout\n";
        ret += "# Profit 1: Stop losing.\n";
        ret += "# Profit 2: Reach max wrong prediction limit\n";
        ret += "# Profit 3: Remaining transactions.\n";
        ret += "# Profit 4: Touched boundaries.\n";
        ret += "# -------------------------------------------------------\n";
        ret += "# Profit 0 = " + profit0 + "\n";
        ret += "# Profit 1 = " + profit1 + "\n";
        ret += "# Profit 2 = " + profit2 + "\n";
        ret += "# Profit 3 = " + profit3 + "\n";
        ret += "# Profit 4 = " + profit4 + "\n";
        return ret += "# Final profit = " + (profit0 + profit1 + profit2 + profit3 + profit4);
    }

    private void saveAsJpeg(File outFile) throws IOException {
        final XYSeriesCollection data = new XYSeriesCollection();

        XYSeries priceSeries = new XYSeries("Price");
        XYSeries upperSeries = new XYSeries("Upper");
        XYSeries lowerSeries = new XYSeries("Lower");
        double max = Double.MIN_VALUE;
        double min = Double.MAX_VALUE;
        long initTime = bbandBuilder.getBBandSequence().get(0).dateStart.getTime();
        for(BBandUnit bbandUnit : bbandBuilder.getBBandSequence()) {
            double upper = bbandUnit.upperBound;
            double lower = bbandUnit.lowerBound;
            if(upper == Double.MAX_VALUE || lower == Double.MIN_VALUE) {
                // initializing part
                continue;
            }
            long t1 = (bbandUnit.dateStart.getTime() - initTime)/1000;
            long t2 = (bbandUnit.dateEnd.getTime() - initTime)/1000;

            // priceSeries.add(t1, bbandUnit.start);
            priceSeries.add(t2, bbandUnit.end);

            if(upper != 0) {
                data.addSeries(upperSeries);
                // upperSeries.add(t1, upper);
                upperSeries.add(t2, upper);

                if(upper > max) max = upper;
                if(upper < min) min = upper;
            }
            if(lower != 0) {

                // lowerSeries.add(t1, lower);
                lowerSeries.add(t2, lower);
                data.addSeries(lowerSeries);

                if(lower > max) max = lower;
                if(lower < min) min = lower;
            }

        }

        data.addSeries(priceSeries);

        for(Transaction trans : allTransactions) {
            XYSeries transSeries = new XYSeries(formatter.format(trans.birthday));
            long t1 = (trans.birthday.getTime() - initTime)/1000;
            long t2 = (trans.dateOffset.getTime() - initTime)/1000;
            transSeries.add(t1, trans.price);
            transSeries.add(t2, trans.offsetValue);
            data.addSeries(transSeries);
        }

        final JFreeChart chart = ChartFactory.createXYLineChart("Oracle", "Time", "Point", data,
            PlotOrientation.VERTICAL, false, true, false);
        chart.setAntiAlias(false);
        XYPlot plot = (XYPlot) chart.getXYPlot();
        plot.setBackgroundPaint(java.awt.Color.BLACK);
        int seriesCount = plot.getSeriesCount();
        for (int i = 0; i < seriesCount; i++) {
            plot.getRenderer().setSeriesStroke(i, new BasicStroke(3));
        }
        final NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setRange(min-20, max+20);
        rangeAxis.setTickUnit(new NumberTickUnit(10));
        final NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setTickUnit(new NumberTickUnit(60));
        domainAxis.setRange(0, 17940);
        domainAxis.setVerticalTickLabels(true);
        int width = 1280*10; /* Width of the image */
        int height = 720; /* Height of the image */
        ChartUtilities.saveChartAsJPEG(outFile, 1.0f, chart, width, height);
    }


    public static void main(String... args) {
        if(args.length == 0) {
            System.out.println("append the input file after the command, please.");
        }
        else {
            // for testing
            for(String s : args) {
                System.out.println(s);
            }
            Oracle oracle = new Oracle();
            oracle.logfileTest(args[0]);
            // System.out.println(oracle.bbandBuilder);
            oracle.finishRemaining();
            System.out.println(oracle);
            // for network streaming input test
            // String line = getNetworkInput();
            // streamingInput(line);

            // Write out bband data points
            // try {
            //     String filename = args[0].split("/")[2];
            //     File outFile = new File("output/bband/" + filename);
            //     PrintWriter pw = new PrintWriter(new FileWriter(outFile));
            //     pw.println(oracle.bbandBuilder);
            //     pw.close();
            // }
            // catch(IOException e) {
            //     e.printStackTrace();
            // }

            // Write out transaction data points
            try {
                String filename = args[0].split("/")[2];
                File outFile = new File("output/transaction/" + filename);
                PrintWriter pw = new PrintWriter(new FileWriter(outFile));
                pw.println(oracle);
                pw.close();
            }
            catch(IOException e) {
                e.printStackTrace();
            }
            // Write out graph
            // try {
            //     String filename = args[0].split("/")[2].split("\\.")[0];
            //     File outFile = new File("output/chart/" + filename + ".jpg");
            //     oracle.saveAsJpeg(outFile);
            // }
            // catch(IOException e) {
            //     e.printStackTrace();
            // }
        }
    }
}
