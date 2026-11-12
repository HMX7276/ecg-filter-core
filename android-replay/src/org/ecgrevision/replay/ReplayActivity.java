package org.ecgrevision.replay;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.widget.*;
import android.view.WindowManager;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import com.ecg.filter.streaming.OverlapAddStream;

/** On-device accelerated replay of public ECG. No network or patient-data access. */
public final class ReplayActivity extends Activity {
    private TextView status;
    private Button start;
    private volatile boolean running;
    private volatile boolean completed;
    private final String[] ids={"mitdb_100","mitdb_207","fantasia_f1o01"};
    private static final int WARMUPS=5, REPEATS=20, CHUNK=1250;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(30,60,30,30);
        TextView title=new TextView(this);title.setText("ECG Replay Research");title.setTextSize(24);box.addView(title);
        status=new TextView(this);status.setTextSize(16);
        status.setText("公开心电数据回放测试\n设备："+Build.MANUFACTURER+" "+Build.MODEL+"\n\n测试会记录计算耗时、内存采样和输出误差。请保持应用在前台。完成后结果保存在应用的外部文件目录。");
        box.addView(status);start=new Button(this);start.setText("开始回放测试");box.addView(start);setContentView(box);
        start.setOnClickListener(v -> {if(completed){finishAndRemoveTask();android.os.Process.killProcess(android.os.Process.myPid());}else launch();});
        if(getIntent().getBooleanExtra("autorun",false))launch();
    }

    private void launch() {
        if(running)return;running=true;start.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            try {runBenchmark();} catch(Throwable error) {
                android.util.Log.e("ECGReplay","Replay failed",error);
                update("测试失败："+error.toString());
                try {File f=new File(getExternalFilesDir(null),"last_error.txt");try(PrintWriter out=new PrintWriter(f)){error.printStackTrace(out);}}catch(Exception ignored){}
            } finally {runOnUiThread(() -> {running=false;start.setEnabled(true);if(completed)start.setText("退出测试程序");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});}
        },"ECGReplayWorker").start();
    }
    private void update(String message) {runOnUiThread(() -> status.setText(message));}

    private void runBenchmark() throws Exception {
        android.content.SharedPreferences preferences=getSharedPreferences("replays",MODE_PRIVATE);
        int session=getIntent().hasExtra("session")?getIntent().getIntExtra("session",1):preferences.getInt("last_manual_session",0)+1;
        if(!getIntent().hasExtra("session"))preferences.edit().putInt("last_manual_session",session).commit();
        String run="session_"+session+"_"+System.currentTimeMillis();
        File directory=new File(getExternalFilesDir(null),run);
        if(!directory.mkdirs())throw new IOException("Cannot create result directory");
        JSONObject result=new JSONObject();result.put("session",session);result.put("run",run);
        result.put("started_utc_ms",System.currentTimeMillis());result.put("device",deviceInfo());
        result.put("process_id",android.os.Process.myPid());result.put("process_elapsed_realtime_at_start_ms",SystemClock.elapsedRealtime());
        result.put("input_manifest",new JSONObject(new String(asset("manifest.json"),StandardCharsets.UTF_8)));
        result.put("apk_sha256",hash(new File(getApplicationInfo().sourceDir)));
        result.put("warmup_replays",WARMUPS);result.put("measured_replays_per_input",REPEATS);
        result.put("timing_includes","accept: input validation, SQIs, calibration/selection, SOS, overlap-add and output allocation; finish measured separately");
        result.put("timing_excludes","asset IO, caller chunk preparation, stream construction, output copying, logging, memory/power sampling, acquisition");
        result.put("buffering","250 Hz; 10 s window, 5 s hop; first output after 10 s input; accelerated replay is not sensor-to-output latency");
        JSONArray inputResults=new JSONArray();
        for(int index=0;index<ids.length;index++) {
            String id=ids[(index+Math.floorMod(session-1,ids.length))%ids.length];
            update("正在测试 "+id+"\n预热 "+WARMUPS+" 次，计时 "+REPEATS+" 次\n请保持应用在前台。");
            byte[] inputBytes=asset(id+"_input.f64"), referenceBytes=asset(id+"_reference.f64");
            double[] input=doubles(inputBytes),reference=doubles(referenceBytes);
            double[][] chunks=new double[(input.length+CHUNK-1)/CHUNK][];
            for(int c=0;c<chunks.length;c++)chunks[c]=Arrays.copyOfRange(input,c*CHUNK,Math.min(input.length,(c+1)*CHUNK));
            JSONObject item=new JSONObject();item.put("input",id);item.put("samples",input.length);
            item.put("input_sha256",hash(inputBytes));item.put("reference_sha256",hash(referenceBytes));item.put("before",snapshot());
            Replay validation=null;
            for(int w=0;w<WARMUPS;w++){Replay r=replay(chunks,input.length,null,null,-1);if(w==0)validation=r;}
            item.put("correctness",compare(validation.output,reference));
            writeDoubles(new File(directory,id+"_output.f64"),validation.output);
            long[] times=new long[REPEATS*(chunks.length-1)];int nt=0;
            StringBuilder frameLog=new StringBuilder("replay,chunk,output_samples,elapsed_ns,java_used_bytes\n");
            StringBuilder replayLog=new StringBuilder("replay,compute_ns,full_frames,finish_ns,finish_samples,java_used_before,java_used_observed_max\n");
            JSONArray power=new JSONArray();long overallHeap=0,total=0;
            for(int r=0;r<REPEATS;r++) {
                Replay one=replay(chunks,input.length,frameLog,times,nt);nt+=one.frames;
                total+=one.compute;overallHeap=Math.max(overallHeap,one.heapMax);
                replayLog.append(r).append(',').append(one.compute).append(',').append(one.frames).append(',').append(one.finishNs).append(',').append(one.finishSamples).append(',').append(one.heapBefore).append(',').append(one.heapMax).append('\n');
                JSONObject snap=snapshot();snap.put("replay",r);power.put(snap);
                // Correctness is checked for every replay; the first output is also saved for host verification.
                JSONObject check=compare(one.output,reference);
                if(check.getDouble("max_abs_uv")>1e-4)throw new IllegalStateException("Output changed: "+check);
            }
            item.put("after",snapshot());item.put("environment_per_replay",power);
            item.put("observed_max_java_used_bytes",overallHeap);item.put("memory_scope","Whole app including loaded inputs, reference, result and log buffers. Sampled Java used heap is not peak live stream state.");
            item.put("full_frame_ns",statistics(Arrays.copyOf(times,nt)));item.put("mean_replay_compute_ms",total/1e6/REPEATS);
            inputResults.put(item);
            write(new File(directory,id+"_frames.csv"),frameLog.toString());write(new File(directory,id+"_replays.csv"),replayLog.toString());
            result.put("inputs",inputResults);write(new File(directory,"results.json"),result.toString(2));
        }
        result.put("completed_utc_ms",System.currentTimeMillis());result.put("status","complete");
        write(new File(directory,"results.json"),result.toString(2));write(new File(getExternalFilesDir(null),"latest_run.txt"),run);
        write(new File(directory,"DONE"),"complete\n");
        String exported=exportToDownloads(directory,run);
        android.util.Log.i("ECGReplay","COMPLETE "+directory.getAbsolutePath());
        completed=true;
        update("第 "+session+" 次回放测试完成\n"+run+"\n\n结果已导出："+exported+"\n\n请点击下方“退出测试程序”，再从桌面重新打开。总共完成 3 次即可。");
    }

    private String exportToDownloads(File directory,String run) throws Exception {
        if(Build.VERSION.SDK_INT<29)return directory.getAbsolutePath();
        ContentValues values=new ContentValues();values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,run+".zip");
        values.put(android.provider.MediaStore.Downloads.MIME_TYPE,"application/zip");values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/ECGReplay");values.put(android.provider.MediaStore.Downloads.IS_PENDING,1);
        android.net.Uri uri=getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("Cannot create Downloads result");
        try(OutputStream raw=getContentResolver().openOutputStream(uri);ZipOutputStream zip=new ZipOutputStream(raw)) {
            File[] files=directory.listFiles();if(files==null)throw new IOException("Cannot list test output");
            for(File file:files){if(!file.isFile())continue;zip.putNextEntry(new ZipEntry(file.getName()));try(InputStream in=new FileInputStream(file)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))>=0)zip.write(buf,0,n);}zip.closeEntry();}
        }
        ContentValues complete=new ContentValues();complete.put(android.provider.MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(uri,complete,null,null);
        return "Download/ECGReplay/"+run+".zip";
    }

    private Replay replay(double[][] chunks,int length,StringBuilder log,long[] times,int offset) {
        OverlapAddStream stream=new OverlapAddStream();Replay r=new Replay();r.output=new double[length];
        int dest=0;r.heapBefore=heap();r.heapMax=r.heapBefore;
        int replayIndex=offset<0?-1:offset/(chunks.length-1);
        for(int c=0;c<chunks.length;c++) {
            long begin=System.nanoTime();double[] out=stream.accept(chunks[c]);long ns=System.nanoTime()-begin;
            r.compute+=ns;long used=heap();r.heapMax=Math.max(r.heapMax,used);
            if(out.length>0){if(times!=null)times[offset+r.frames]=ns;r.frames++;}
            if(log!=null)log.append(replayIndex).append(',').append(c).append(',').append(out.length).append(',').append(ns).append(',').append(used).append('\n');
            System.arraycopy(out,0,r.output,dest,out.length);dest+=out.length;
        }
        long begin=System.nanoTime();double[] tail=stream.finish();r.finishNs=System.nanoTime()-begin;r.finishSamples=tail.length;r.compute+=r.finishNs;
        System.arraycopy(tail,0,r.output,dest,tail.length);dest+=tail.length;
        r.heapMax=Math.max(r.heapMax,heap());if(dest!=length)throw new IllegalStateException("Output length mismatch");return r;
    }
    private static final class Replay {double[] output;long compute,finishNs,heapBefore,heapMax;int frames,finishSamples;}
    private static long heap(){Runtime rt=Runtime.getRuntime();return rt.totalMemory()-rt.freeMemory();}
    private JSONObject compare(double[] x,double[] ref) throws Exception {
        if(x.length!=ref.length)throw new IllegalStateException("Reference length mismatch");
        double sq=0,max=0;for(int i=0;i<x.length;i++){if(!Double.isFinite(x[i]))throw new IllegalStateException("Nonfinite output");double d=x[i]-ref[i];sq+=d*d;max=Math.max(max,Math.abs(d));}
        JSONObject o=new JSONObject();o.put("samples",x.length);o.put("rmse_uv",Math.sqrt(sq/x.length));o.put("max_abs_uv",max);return o;
    }
    private JSONObject deviceInfo() throws Exception {
        JSONObject o=new JSONObject();o.put("manufacturer",Build.MANUFACTURER);o.put("model",Build.MODEL);o.put("device",Build.DEVICE);o.put("hardware",Build.HARDWARE);
        o.put("fingerprint",Build.FINGERPRINT);o.put("android_release",Build.VERSION.RELEASE);o.put("api",Build.VERSION.SDK_INT);o.put("security_patch",Build.VERSION.SECURITY_PATCH);
        o.put("abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));o.put("java_vm",System.getProperty("java.vm.version"));o.put("logical_processors",Runtime.getRuntime().availableProcessors());
        if(Build.VERSION.SDK_INT>=31){o.put("soc_manufacturer",Build.SOC_MANUFACTURER);o.put("soc_model",Build.SOC_MODEL);}
        o.put("debuggable",(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);o.put("debugger_connected",Debug.isDebuggerConnected());
        o.put("thread_priority",android.os.Process.getThreadPriority(android.os.Process.myTid()));return o;
    }
    private JSONObject snapshot() throws Exception {
        JSONObject o=new JSONObject();o.put("utc_ms",System.currentTimeMillis());o.put("java_used_bytes",heap());o.put("java_total_bytes",Runtime.getRuntime().totalMemory());o.put("java_max_bytes",Runtime.getRuntime().maxMemory());
        Debug.MemoryInfo m=new Debug.MemoryInfo();Debug.getMemoryInfo(m);o.put("total_pss_kb",m.getTotalPss());o.put("native_heap_allocated_bytes",Debug.getNativeHeapAllocatedSize());
        o.put("runtime_stats",new JSONObject(Debug.getRuntimeStats()));
        PowerManager p=(PowerManager)getSystemService(POWER_SERVICE);o.put("power_save",p.isPowerSaveMode());o.put("interactive",p.isInteractive());if(Build.VERSION.SDK_INT>=29)o.put("thermal_status",p.getCurrentThermalStatus());
        Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(b!=null){o.put("battery_level",b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1));o.put("battery_scale",b.getIntExtra(BatteryManager.EXTRA_SCALE,-1));o.put("plugged",b.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1));o.put("battery_temperature_tenths_c",b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1));}
        return o;
    }
    private static JSONObject statistics(long[] values) throws Exception {Arrays.sort(values);double sum=0;for(long v:values)sum+=v;JSONObject o=new JSONObject();o.put("n",values.length);o.put("mean",sum/values.length);o.put("median",(values[(values.length-1)/2]+values[values.length/2])/2.0);o.put("p95_nearest_rank",values[(int)Math.ceil(.95*values.length)-1]);o.put("maximum",values[values.length-1]);return o;}
    private byte[] asset(String name)throws IOException {try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toByteArray();}}
    private static double[] doubles(byte[] b){if(b.length%8!=0)throw new IllegalArgumentException("Invalid float64 bytes");DoubleBuffer db=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer();double[] x=new double[db.remaining()];db.get(x);return x;}
    private static void writeDoubles(File path,double[] x)throws IOException {ByteBuffer b=ByteBuffer.allocate(x.length*8).order(ByteOrder.LITTLE_ENDIAN);for(double v:x)b.putDouble(v);try(FileOutputStream out=new FileOutputStream(path)){out.write(b.array());}}
    private static void write(File path,String s)throws IOException {try(FileOutputStream out=new FileOutputStream(path)){out.write(s.getBytes(StandardCharsets.UTF_8));}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static String hash(byte[] b)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static String hash(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}return hex(d.digest());}
}
