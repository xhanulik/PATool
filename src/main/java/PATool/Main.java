package PATool;

import com.beust.jcommander.ParameterException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.ptr.*;
import cz.muni.fi.crocs.rcard.client.CardManager;
import cz.muni.fi.crocs.rcard.client.CardType;
import cz.muni.fi.crocs.rcard.client.RunConfig;
import cz.muni.fi.crocs.rcard.client.Util;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.security.Security;
import java.util.Arrays;

import static java.lang.Integer.toHexString;

public class Main {

    private static final String APPLET_AID = "12345678900101";
    static byte[] secret = stringToByteArray("55389167C900028A37A264541AE18C5733902C0B51D7665ED41AFE6788FE9FBA");
    static byte[] point = stringToByteArray("042B32AB827BDFFA6F63CCF9F27B1D03017F4F5D909C13294E8A4C389D3F57373F767033B8932942ADED1A0EC48D9A1E1D26FB1EEC023F74AA48F6A891E73076CA");
    static byte[] hidingNonceRandomness = stringToByteArray("55389167C900028A37A264541AE18C5733902C0B51D7665ED41AFE6788FE9FBA");
    static byte[] bindingNonceRandomness = stringToByteArray("55389167C900028A37A264541AE18C5733902C0B51D7665ED41AFE6788FE9FBA");
    static byte[] msg = stringToByteArray("A14B0F3B0122E2687C50D0D436277F1B");
    private static final byte[] APPLET_AID_BYTE = Util.hexStringToByteArray(APPLET_AID);
    static Path csv = FileSystems.getDefault().getPath("./measurements.csv");
    static CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT).setCommentMarker('#')
            .setRecordSeparator(System.lineSeparator())
            .build();

    public static final String[] infoDescription = {"Driver Version", "USB Version", "Hardware Version", "Variant", "Batch/Serial", "Cal Date", "Error Code", "Kernel Driver"};

    public static void main(final String[] argv) throws IOException {
        System.out.println("Starting PATool");

        System.out.println("Setting up PicoScope device:");
        short status;

        // Open device
        System.out.println("\tOpening PicoScope 2000 Series device ...");
        System.out.println();

        short handle = PS2000CLibrary.INSTANCE.ps2000_open_unit();

        System.out.println("\tHandle: " + handle);

        if (handle <= 0) {
            System.err.println("\tUnable to open device.");
            System.exit(-1);
        }

        // Print unit information to console
        byte[] infoBytes = new byte[80];
        for (short i = 0; i < infoDescription.length; i ++) {
            PS2000CLibrary.INSTANCE.ps2000_get_unit_info(handle, infoBytes, (short) infoBytes.length, i);
            if (i != 6) {
                System.out.println("\t" + infoDescription[i] + ": \t" + Native.toString(infoBytes));
            }
        }
        System.out.println();

        short channelA = (short) PS2000CLibrary.PS2000Channel.PS2000_CHANNEL_A.ordinal();
        short chARange = (short) PS2000CLibrary.PS2000Range.PS2000_10V.ordinal();

        // Channel A: enabled, DC coupling, +/- 5V
        status = PS2000CLibrary.INSTANCE.ps2000_set_channel(handle, channelA, /* enabled */ (short) 1, /* DC coupling */ (short) 1, chARange);
        if (status == 0) {
            System.err.println("\tps2000_set_channel: Error setting channel A.");
            closeDeviceOnError(handle);
        }

        // Setup trigger, set threshold to 500 mv
        double mvThreshold = 500.0;
        short threshold = (short) (mvThreshold / VoltageDefinitions.SCOPE_INPUT_RANGES_MV[chARange] * PS2000CLibrary.PS2000_MAX_VALUE);
        short direction = (short) PS2000CLibrary.PS2000TriggerDirection.PS2000_RISING.ordinal();
        float delay = -10.0f; // Place trigger after 10%
        short autoTriggerMs = 1000; // Wait for 2 seconds (set to 0 to wait indefinitely)
        status = PS2000CLibrary.INSTANCE.ps2000_set_trigger2(handle, channelA, threshold, direction, delay, autoTriggerMs);
        if (status == 0) {
            System.err.println("\tps2000_set_trigger2: Error setting trigger.");
            closeDeviceOnError(handle);
        }

        // Preselect the timebase for 25 MS/s
        short timebase = 2; // 25 MS/s with PicoScope 2204A
        int numberOfSamples = 4096;
        short oversample = 1;

        IntByReference timeIntervalIbr = new IntByReference(0);
        ShortByReference timeUnitsSbr = new ShortByReference((short) 0);
        IntByReference maxSamplesIbr = new IntByReference(0);

        do {
            status = PS2000CLibrary.INSTANCE.ps2000_get_timebase(handle, timebase, numberOfSamples, timeIntervalIbr, timeUnitsSbr, oversample, maxSamplesIbr);
            // If invalid timebase is used, increment timebase index
            if(status == 0) {
                timebase++;
            }
        } while (status == 0);
        System.out.println("\tTimebase: " + timebase);
        System.out.println("\tTime interval: " + timeIntervalIbr);
        System.out.println("\tTime unit: " + timeUnitsSbr);
        System.out.println("\tMax samples: " + maxSamplesIbr);

        System.out.println("Preparing card for measuring");
        CardManager cardManager = setupConnection();

        sendAPDU(cardManager, 0x00, 0x00, 0x00, null, "\tSetting up card");
        sendAPDU(cardManager, 0x01, 0x02, 0x02,  Util.concat(new byte[]{(byte) 1}, secret, recodePoint(point)), "\tInitializing card");
        byte[] cardData = sendAPDU(cardManager, 0x02, 64, 0x00,  Util.concat(hidingNonceRandomness, bindingNonceRandomness), "\tCommit");
        byte[] hiding = Arrays.copyOfRange(cardData, 0, 33);
        byte[] binding = Arrays.copyOfRange(cardData, 33, 66);
        System.out.println("\tSending commitments from other cards");
        sendAPDU(cardManager, 0x03, 0x01, 0x00,  Util.concat(recodePoint(hiding), recodePoint(binding)), "\t\tCard 1");
        sendAPDU(cardManager, 0x03, 0x02, 0x00,  Util.concat(recodePoint(hiding), recodePoint(binding)), "\t\tCard 2");

        System.out.println("Starting measuring");
        // Start collecting data
        IntByReference timeIndisposedMsIbr = new IntByReference(0);
        System.out.println("\tStarting data collection (waiting for trigger)...");

        status = PS2000CLibrary.INSTANCE.ps2000_run_block(handle, numberOfSamples, timebase, oversample, timeIndisposedMsIbr);
        if (status == 0) {
            System.err.println("\tps2000_run_block: A parameter is out of range.");
            closeDeviceOnError(handle);
        }
        sendAPDU(cardManager, 0x04, 0x10, 0x00,  msg, "\tSigning message");

        // Poll the driver until the device has completed data collection
        short ready = 0;
        while (ready == 0) {
            ready = PS2000CLibrary.INSTANCE.ps2000_ready(handle);
            System.out.print(".");

            try {
                Thread.sleep(5);
            } catch(InterruptedException ie) {
                ie.printStackTrace();
                closeDeviceOnError(handle);
            }
        }

        System.out.println();

        if (ready > 0){
            System.out.println("Data collection completed");

            // Retrieve data values
            Memory timesPointer = new Memory((long) numberOfSamples * Native.getNativeSize(Integer.TYPE));
            Memory chABufferPointer = new Memory((long) numberOfSamples * Native.getNativeSize(Short.TYPE));
            ShortByReference overflowSbr = new ShortByReference((short) 0);

            int numberOfSamplesCollected = PS2000CLibrary.INSTANCE.ps2000_get_times_and_values(handle, timesPointer, chABufferPointer, null,
                    null, null, overflowSbr, timeUnitsSbr.getValue(), numberOfSamples);

            if (numberOfSamplesCollected > 0) {
                System.out.println("\tCollected " + numberOfSamples + " samples.");
                System.out.println();

                int[] times = timesPointer.getIntArray(0, numberOfSamplesCollected);
                short[] chAData = chABufferPointer.getShortArray(0, numberOfSamplesCollected);
                float[] chADataMiliVolts = adc2mV(chAData, chARange);

                System.out.println("Time\t ChA (ADC Counts)");
                CSVPrinter printer = new CSVPrinter(new FileWriter(csv.toFile()), format);
                for(int i = 0; i < numberOfSamplesCollected; i++) {
                    System.out.println(times[i] + ",\t" + chADataMiliVolts[i]);
                    printer.print(times[i]);
                    printer.printRecord(chADataMiliVolts[i]);
                }
            } else {
                System.err.println("\tps2000_get_times_and_values: No samples collected.");
            }
        }
        sendAPDU(cardManager, 0x05, 0x00, 0x00,  null, "\tResetting card");

        // Stop the oscilloscopes
        PS2000CLibrary.INSTANCE.ps2000_stop(handle);

        // Close the unit
        PS2000CLibrary.INSTANCE.ps2000_close_unit(handle);
        System.out.println("\nExiting application.");
    }

    private static float[] adc2mV(short[] chAData, int range) {
        int vRange = VoltageDefinitions.SCOPE_INPUT_RANGES_MV[range];
        float[] result = new float[chAData.length];
        for (int i = 0; i < chAData.length; i++) {
            result[i] =  ((float) chAData[i] * vRange) / PS2000CLibrary.PS2000_MAX_VALUE;
        }
        return result;
    }

    private static void closeDeviceOnError(short handle)
    {
        PS2000CLibrary.INSTANCE.ps2000_close_unit(handle);
        System.exit(-1);
    }

    public static byte[] stringToByteArray(String value) {
        if (value.startsWith("0x"))
            value = value.trim().replaceFirst("^0x", "");

        if (value.length() % 2 == 1)
            throw new ParameterException("Invalid hex string");

        try {
            // check that value is a hexstring
            return Util.hexStringToByteArray(value);
        } catch (NumberFormatException e) {
            throw new ParameterException("Invalid hex string.");
        }
    }

    public static byte[] recodePoint(byte[] point) {
        Security.addProvider(new BouncyCastleProvider());
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        return spec.getCurve().decodePoint(point).getEncoded(false);
    }

    private static CardManager setupConnection() {
        CardManager cardManager = new CardManager(false, Main.APPLET_AID_BYTE);
        final RunConfig runCfg = RunConfig.getDefaultConfig();
        runCfg.setTestCardType(CardType.PHYSICAL);
        System.out.print("Connecting to card...");
        try {
            if (!cardManager.connect(runCfg)) {
                System.out.println(" failed.");
                System.exit(-1);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        System.out.println(" connected.");
        return cardManager;
    }

    private static byte[] sendAPDU(CardManager cardManager, int ins, int p1, int p2, byte[] data, String task) {
        System.out.print(task + "... ");
        ResponseAPDU response = null;
        try {
            response = cardManager.transmit(new CommandAPDU(0x00, ins, p1, p2, data));
        } catch (Exception e) {
            System.out.println("Transmission failed.");
            System.exit(-1);
        }
        System.out.println(" response: " + toHexString(response.getSW1()) + " " + toHexString(response.getSW2()));
        return response.getData();
    }
}
