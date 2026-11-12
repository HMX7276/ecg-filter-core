import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.ecg.filter.streaming.OverlapAddStream;

/** Desktop replay benchmark. Does not measure acquisition delay or Android. */
public class RevisionBenchmark {
    private static volatile double sink;
    public static void main(String[] args) throws Exception {
        ByteBuffer buffer=ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        double[][] chunks=new double[buffer.remaining()/8/1250][1250];
        for(double[] c:chunks) for(int i=0;i<c.length;i++) c[i]=buffer.getDouble();
        for(int i=0;i<5;i++) replay(chunks,null);
        List<Double> ms=new ArrayList<>(); double elapsed=0;
        for(int i=0;i<20;i++) { long start=System.nanoTime(); replay(chunks,ms); elapsed+=(System.nanoTime()-start)/1e6; }
        Collections.sort(ms); double mean=0; for(double v:ms) mean+=v/ms.size();
        System.out.printf(Locale.ROOT,"{\"warmup_replays\":5,\"measured_replays\":20,\"measured_full_frames\":%d,\"mean_ms\":%.6f,\"median_ms\":%.6f,\"p95_ms\":%.6f,\"max_ms\":%.6f,\"mean_replay_ms\":%.6f,\"duration_s\":600,\"window_s\":10,\"hop_s\":5,\"includes\":\"BWA, centered mSQI, calibration, selection, SOS, overlap-add\",\"excludes\":\"acquisition, disk IO, caller chunk preparation\"}%n",ms.size(),mean,ms.get(ms.size()/2),ms.get((int)Math.ceil(.95*ms.size())-1),ms.get(ms.size()-1),elapsed/20);
    }
    private static void replay(double[][] chunks,List<Double> ms) {
        OverlapAddStream stream=new OverlapAddStream();
        for(double[] c:chunks) {
            long start=System.nanoTime(); double[] y=stream.accept(c); double elapsed=(System.nanoTime()-start)/1e6;
            if(y.length>0) { if(ms!=null) ms.add(elapsed); sink=y[y.length-1]; }
        }
        double[] tail=stream.finish(); sink=tail[tail.length-1];
    }
}
