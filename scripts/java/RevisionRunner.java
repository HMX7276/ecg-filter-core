import java.nio.file.*;
import java.io.*;
import java.util.*;
import com.ecg.filter.adaptive.*;
import com.ecg.filter.butterworth.*;
import com.ecg.filter.sqi.*;
import com.ecg.filter.streaming.*;

/** Binary little-endian float64 bridge. One JVM can execute a TSV job manifest. */
public final class RevisionRunner {
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("manifest")) {
            for(String line:Files.readAllLines(Paths.get(args[1]))) if(!line.trim().isEmpty()) run(line.split("\t"));
        } else run(args);
    }
    private static void run(String[] a) throws Exception {
        String mode=a[0]; double[] x=read(a[1]); double[] y;
        FilterConfig config=FilterConfig.valueOf(a.length>3?a[3]:"CONFIG_DEFAULT");
        if(mode.equals("sos")) y=new SosZeroPhaseFilter().filter(x,config);
        else if(mode.equals("direct")) y=new ZeroPhaseButterworthFilter().filter(x,config);
        else if(mode.equals("custom")) {
            double[] flat=read(a[4]); double[][] sos=new double[flat.length/6][6];
            for(int i=0;i<sos.length;i++) System.arraycopy(flat,6*i,sos[i],0,6);
            y=new SosZeroPhaseFilter().sosfiltfilt(x,sos);
        } else if(mode.equals("features") || mode.equals("features_raw")) {
            int win=2500,hop=1250,n=Math.max(0,(x.length-win)/hop+1);
            y=new double[n*2]; BaselineWanderAmplitude b=new BaselineWanderAmplitude(); MuscleSQI m=new MuscleSQI();
            for(int i=0;i<n;i++) { double[] frame=Arrays.copyOfRange(x,i*hop,i*hop+win); y[2*i]=b.compute(frame,250); y[2*i+1]=mode.equals("features_raw")?m.compute(frame,250):new CenteredMuscleSQI().compute(frame,250); }
        } else if(mode.equals("stream") || mode.equals("adaptive") || mode.equals("adaptive_raw")) {
            OverlapAddStream stream=mode.equals("stream")?new OverlapAddStream(config):new OverlapAddStream(new SessionCalibratedSelector(),!mode.equals("adaptive_raw"));
            y=new double[x.length]; int dest=0; int chunk=a.length>4?Integer.parseInt(a[4]):997;
            for(int start=0;start<x.length;start+=chunk) {
                double[] output=stream.accept(Arrays.copyOfRange(x,start,Math.min(x.length,start+chunk)));
                System.arraycopy(output,0,y,dest,output.length); dest+=output.length;
            }
            double[] tail=stream.finish(); System.arraycopy(tail,0,y,dest,tail.length); dest+=tail.length;
            if(dest!=x.length) throw new AssertionError("Output length differs");
        } else throw new IllegalArgumentException("Unknown mode: "+mode);
        write(a[2],y);
    }
    private static double[] read(String path) throws Exception {
        byte[] b=Files.readAllBytes(Paths.get(path));
        if(b.length%8!=0) throw new IllegalArgumentException("Invalid float64 file");
        java.nio.ByteBuffer buf=java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        double[] x=new double[b.length/8]; for(int i=0;i<x.length;i++) x[i]=buf.getDouble(); return x;
    }
    private static void write(String path,double[] x) throws Exception {
        java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(x.length*8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for(double v:x) b.putDouble(v); Files.write(Paths.get(path),b.array());
    }
}
