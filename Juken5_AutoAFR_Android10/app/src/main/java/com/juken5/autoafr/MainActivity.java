package com.juken5.autoafr;

import android.app.*;
import android.os.*;
import android.bluetooth.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;

public class MainActivity extends Activity {
    static final int TPS_CELLS=21, RPM_CELLS=61;
    final int[] rpms=new int[RPM_CELLS];
    final double[] tps=new double[TPS_CELLS];
    final double[][] map=new double[TPS_CELLS][RPM_CELLS];
    final double[][] accum=new double[TPS_CELLS][RPM_CELLS];
    final int[][] samples=new int[TPS_CELLS][RPM_CELLS];
    EditText rpm,tpsIn,afr,target,gain,deadband,maxcorr;
    TextView status,live,selection;
    TableLayout table;
    boolean tuning=false;
    BluetoothSocket socket;
    BufferedReader reader;
    BufferedWriter writer;
    Thread rx;
    final DecimalFormat df=new DecimalFormat("0.0");

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main);
        rpm=findViewById(R.id.rpm); tpsIn=findViewById(R.id.tps); afr=findViewById(R.id.afr);
        target=findViewById(R.id.target); gain=findViewById(R.id.gain); deadband=findViewById(R.id.deadband);
        maxcorr=findViewById(R.id.maxcorr); status=findViewById(R.id.status); live=findViewById(R.id.live);
        selection=findViewById(R.id.selection); table=findViewById(R.id.mapTable);
        initAxes(); initMap(); renderTable(); updateSelection();
        findViewById(R.id.autoTuneStart).setOnClickListener(v->{tuning=true; status.setText("AUTO TUNE aktif — koreksi lokal berdasarkan AFR.");});
        findViewById(R.id.autoTuneStop).setOnClickListener(v->{tuning=false; status.setText("AUTO TUNE berhenti.");});
        findViewById(R.id.applyCell).setOnClickListener(v->applySample());
        findViewById(R.id.reset).setOnClickListener(v->{initMap();renderTable();status.setText("MAP di-reset ke 100%.");});
        findViewById(R.id.connect).setOnClickListener(v->chooseBluetooth());
        findViewById(R.id.disconnect).setOnClickListener(v->disconnect());
        findViewById(R.id.preview2602).setOnClickListener(v->preview2602());
        findViewById(R.id.export).setOnClickListener(v->exportCsv());
        View.OnFocusChangeListener f=(v,has)->{if(!has) updateSelection();};
        rpm.setOnFocusChangeListener(f); tpsIn.setOnFocusChangeListener(f);
    }

    void initAxes(){
        for(int i=0;i<RPM_CELLS;i++) rpms[i]=i*250;
        for(int i=0;i<TPS_CELLS;i++) tps[i]=i*5.0;
    }
    void initMap(){
        for(int y=0;y<TPS_CELLS;y++) for(int x=0;x<RPM_CELLS;x++){map[y][x]=100.0;accum[y][x]=0;samples[y][x]=0;}
    }
    int cellRpm(double r){return Math.max(0,Math.min(RPM_CELLS-1,(int)Math.round(r/250.0)));}
    int cellTps(double t){return Math.max(0,Math.min(TPS_CELLS-1,(int)Math.round(t/5.0)));}
    void updateSelection(){double r=num(rpm,3000), t=num(tpsIn,20); selection.setText("Sel: TPS "+df.format(t)+"% / "+(int)r+" rpm");}
    double num(EditText e,double d){try{return Double.parseDouble(e.getText().toString().replace(',','.'));}catch(Exception x){return d;}}

    void applySample(){
        double a=num(afr,14.7), ta=num(target,13.8), g=Math.max(0,Math.min(1,num(gain,.5)));
        double db=Math.max(0,num(deadband,.15)), max=Math.max(.1,num(maxcorr,5));
        double r=num(rpm,3000), t=num(tpsIn,20); int x=cellRpm(r), y=cellTps(t);
        double err=a-ta;
        if(Math.abs(err)<db){status.setText("AFR dalam deadband — koreksi 0%.");return;}
        double corr=(ta>0?err/ta*100.0:0)*g;
        corr=Math.max(-max,Math.min(max,corr));
        map[y][x]=Math.max(50,Math.min(150,map[y][x]*(1+corr/100.0)));
        accum[y][x]+=corr; samples[y][x]++;
        renderTable();
        live.setText("LIVE AFR: "+df.format(a)+"  TARGET: "+df.format(ta)+"  CELL CORR: "+df.format(corr)+"%");
        status.setText("Cell TPS "+df.format(tps[y])+" / "+rpms[x]+" rpm → "+df.format(map[y][x])+"%");
    }

    void renderTable(){
        table.removeAllViews();
        TableRow head=new TableRow(this); head.addView(tv("TPS/RPM"));
        for(int x=0;x<RPM_CELLS;x+=5) head.addView(tv(String.valueOf(rpms[x])));
        table.addView(head);
        for(int y=0;y<TPS_CELLS;y++){
            TableRow row=new TableRow(this); row.addView(tv(df.format(tps[y])));
            for(int x=0;x<RPM_CELLS;x+=5) row.addView(tv(df.format(map[y][x])));
            table.addView(row);
        }
    }
    TextView tv(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(10);v.setPadding(5,3,5,3);return v;}

    void chooseBluetooth(){
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=0){
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_SCAN"},90); return;
        }
        BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
        if(a==null){status.setText("Perangkat tidak mendukung Bluetooth.");return;}
        Set<BluetoothDevice> ds=a.getBondedDevices();
        if(ds.isEmpty()){status.setText("Pair modem Bluetooth ECU terlebih dahulu.");return;}
        final BluetoothDevice[] arr=ds.toArray(new BluetoothDevice[0]);
        String[] names=new String[arr.length];
        for(int i=0;i<arr.length;i++)names[i]=String.valueOf(arr[i].getName())+" — "+arr[i].getAddress();
        new AlertDialog.Builder(this).setTitle("Pilih modem ECU").setItems(names,(d,w)->connect(arr[w])).show();
    }
    void connect(BluetoothDevice d){
        new Thread(()->{
            try{
                socket=d.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                socket.connect();
                reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                writer=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
                runOnUiThread(()->status.setText("Bluetooth terhubung: "+d.getName()));
                rx=new Thread(this::readLoop); rx.start();
            }catch(Exception e){runOnUiThread(()->status.setText("Gagal konek Bluetooth: "+e.getMessage()));}
        }).start();
    }
    void readLoop(){
        try{String s;while(socket!=null && socket.isConnected() && (s=reader.readLine())!=null)parseTelemetry(s);}catch(Exception ignored){}
    }
    void parseTelemetry(String s){
        try{
            Double a=null,r=null,t=null;
            for(String p:s.split(",")){String[] q=p.split("=");if(q.length!=2)continue;
                if(q[0].trim().equalsIgnoreCase("AFR"))a=Double.valueOf(q[1].trim());
                if(q[0].trim().equalsIgnoreCase("RPM"))r=Double.valueOf(q[1].trim());
                if(q[0].trim().equalsIgnoreCase("TPS"))t=Double.valueOf(q[1].trim());
            }
            if(a!=null&&r!=null&&t!=null){final double fa=a,fr=r,ft=t;runOnUiThread(()->{
                afr.setText(df.format(fa));rpm.setText(String.valueOf((int)fr));tpsIn.setText(df.format(ft));
                live.setText("LIVE AFR: "+df.format(fa)+" / "+df.format(num(target,13.8)));
                if(tuning)applySample();
            });}
        }catch(Exception ignored){}
    }

    // Reconstructed from the supplied Juken 5 APK bytecode report.
    // 2602;<argument>;<hitung_tps>;<61 fuel values>\\r\\n
    // No CRC/checksum was found in kirimFuelCorrection().
    String build2602Preview(){
        StringBuilder s=new StringBuilder("2602;AUTOAFR;"+cellTps(num(tpsIn,20))+";");
        int y=cellTps(num(tpsIn,20));
        for(int x=0;x<RPM_CELLS;x++){ if(x>0)s.append(";"); s.append(df.format(map[y][x])); }
        s.append("\\r\\n");
        return s.toString();
    }
    void preview2602(){
        String packet=build2602Preview();
        TextView v=new TextView(this); v.setText(packet); v.setTextIsSelectable(true); v.setPadding(16,8,16,8);
        new AlertDialog.Builder(this).setTitle("2602 PREVIEW — TIDAK DIKIRIM").setMessage(packet).setView(v).setPositiveButton("OK",null).show();
        status.setText("Packet 2602 dibuat untuk verifikasi; belum dikirim ke ECU.");
    }

    void disconnect(){try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;status.setText("Bluetooth terputus.");}
    void exportCsv(){
        try{
            File f=new File(getExternalFilesDir(null),"juken5_target_afr_map.csv");
            FileOutputStream o=new FileOutputStream(f);StringBuilder s=new StringBuilder("TPS/RPM");
            for(int x=0;x<RPM_CELLS;x++)s.append(",").append(rpms[x]);s.append("\n");
            for(int y=0;y<TPS_CELLS;y++){s.append(tps[y]);for(int x=0;x<RPM_CELLS;x++)s.append(",").append(df.format(map[y][x]));s.append("\n");}
            o.write(s.toString().getBytes(StandardCharsets.UTF_8));o.close();
            status.setText("Map tersimpan: "+f.getAbsolutePath());
        }catch(Exception e){status.setText("Export gagal: "+e.getMessage());}
    }
    @Override protected void onDestroy(){disconnect();super.onDestroy();}
}