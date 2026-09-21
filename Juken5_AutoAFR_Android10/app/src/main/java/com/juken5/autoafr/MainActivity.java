package com.juken5.autoafr;

import android.app.*;
import android.os.*;
import android.bluetooth.*;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;

public class MainActivity extends Activity {
    static final int TPS_CELLS=21, RPM_CELLS=61;
    static final String PREFS="juken5_map_v7";
    static final String SPP="00001101-0000-1000-8000-00805F9B34FB";
    final int[] rpms=new int[RPM_CELLS];
    final double[] tps=new double[TPS_CELLS];
    final double[][] map=new double[TPS_CELLS][RPM_CELLS];
    final int[][] samples=new int[TPS_CELLS][RPM_CELLS];

    EditText rpm,tpsIn,afr,target,gain,deadband,maxcorr;
    TextView status,live,selection,stats;
    TableLayout table;
    boolean tuning=false;
    BluetoothSocket socket;
    InputStream input;
    OutputStream output;
    Thread rx;
    final DecimalFormat df=new DecimalFormat("0.0");
    int selectedX=12, selectedY=4;

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main);
        rpm=findViewById(R.id.rpm); tpsIn=findViewById(R.id.tps); afr=findViewById(R.id.afr);
        target=findViewById(R.id.target); gain=findViewById(R.id.gain); deadband=findViewById(R.id.deadband);
        maxcorr=findViewById(R.id.maxcorr); status=findViewById(R.id.status); live=findViewById(R.id.live);
        selection=findViewById(R.id.selection); stats=findViewById(R.id.stats); table=findViewById(R.id.mapTable);
        initAxes(); loadMap(); updateSelection(); renderTable();

        findViewById(R.id.autoTuneStart).setOnClickListener(v->{tuning=true; status.setText("AUTO TUNE AKTIF — koreksi map lokal.");});
        findViewById(R.id.autoTuneStop).setOnClickListener(v->{tuning=false; status.setText("AUTO TUNE berhenti.");});
        findViewById(R.id.applyCell).setOnClickListener(v->applySample());
        findViewById(R.id.reset).setOnClickListener(v->confirmReset());
        findViewById(R.id.connect).setOnClickListener(v->chooseBluetooth());
        findViewById(R.id.disconnect).setOnClickListener(v->disconnect());
        findViewById(R.id.save).setOnClickListener(v->saveMap());
        findViewById(R.id.load).setOnClickListener(v->openMap());
        findViewById(R.id.export).setOnClickListener(v->exportCsv());
        findViewById(R.id.importMap).setOnClickListener(v->openMap());
        findViewById(R.id.packet).setOnClickListener(v->showPacket());
        findViewById(R.id.sendTest).setOnClickListener(v->send2602Test());

        View.OnFocusChangeListener f=(v,has)->{if(!has) updateSelection();};
        rpm.setOnFocusChangeListener(f); tpsIn.setOnFocusChangeListener(f);
        updateStats();
    }

    void initAxes(){for(int i=0;i<RPM_CELLS;i++)rpms[i]=i*250;for(int i=0;i<TPS_CELLS;i++)tps[i]=i*5.0;}

    void defaultMap(){for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++){map[y][x]=100; samples[y][x]=0;}}
    void loadMap(){
        defaultMap();
        android.content.SharedPreferences p=getSharedPreferences(PREFS,0);
        boolean found=p.getBoolean("found",false);
        if(found)for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++)map[y][x]=Double.longBitsToDouble(p.getLong("m_"+y+"_"+x,Double.doubleToLongBits(100)));
    }
    void saveMap(){
        android.content.SharedPreferences.Editor e=getSharedPreferences(PREFS,0).edit().putBoolean("found",true);
        for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++)e.putLong("m_"+y+"_"+x,Double.doubleToLongBits(map[y][x]));
        e.apply(); status.setText("MAP tersimpan di HP."); updateStats();
    }

    int cellRpm(double r){return Math.max(0,Math.min(RPM_CELLS-1,(int)Math.round(r/250.0)));}
    int cellTps(double t){return Math.max(0,Math.min(TPS_CELLS-1,(int)Math.round(t/5.0)));}
    double num(EditText e,double d){try{return Double.parseDouble(e.getText().toString().replace(',','.'));}catch(Exception x){return d;}}
    void updateSelection(){
        double r=num(rpm,3000), t=num(tpsIn,20); selectedX=cellRpm(r); selectedY=cellTps(t);
        selection.setText("CELL: TPS "+df.format(tps[selectedY])+"% / RPM "+rpms[selectedX]+"  •  MAP "+df.format(map[selectedY][selectedX])+"%");
    }

    void applySample(){
        double a=num(afr,14.7), ta=num(target,13.8), g=Math.max(0,Math.min(1,num(gain,.5)));
        double db=Math.max(0,num(deadband,.15)), max=Math.max(.1,num(maxcorr,5));
        int x=cellRpm(num(rpm,3000)), y=cellTps(num(tpsIn,20));
        selectedX=x; selectedY=y;
        double err=a-ta;
        if(Math.abs(err)<db){status.setText("AFR dalam deadband — koreksi 0%.");return;}
        double corr=(ta>0?err/ta*100:0)*g;
        corr=Math.max(-max,Math.min(max,corr));
        map[y][x]=Math.max(50,Math.min(150,map[y][x]*(1+corr/100)));
        samples[y][x]++;
        live.setText("LIVE AFR "+df.format(a)+"  TARGET "+df.format(ta)+"  CORR "+df.format(corr)+"%");
        status.setText("Cell TPS "+df.format(tps[y])+" / "+rpms[x]+" → "+df.format(map[y][x])+"%");
        updateSelection(); renderTable(); updateStats(); saveMap();
    }

    void renderTable(){
        table.removeAllViews();
        TableRow head=new TableRow(this); head.addView(cell("TPS/RPM",true));
        for(int x=0;x<RPM_CELLS;x++)head.addView(cell(String.valueOf(rpms[x]),true));
        table.addView(head);
        for(int y=0;y<TPS_CELLS;y++){
            TableRow row=new TableRow(this); row.addView(cell(df.format(tps[y])+"%",true));
            for(int x=0;x<RPM_CELLS;x++){
                TextView c=cell(df.format(map[y][x]),false);
                final int fx=x,fy=y;
                c.setOnClickListener(v->editCell(fy,fx));
                if(x==selectedX&&y==selectedY)c.setText("["+df.format(map[y][x])+"]");
                row.addView(c);
            }
            table.addView(row);
        }
    }
    TextView cell(String s,boolean header){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(header?9:8);v.setPadding(4,4,4,4);
        v.setMinWidth(header?58:52);v.setGravity(Gravity.CENTER);
        return v;
    }
    void editCell(int y,int x){
        selectedX=x;selectedY=y;
        final EditText e=new EditText(this);e.setInputType(2|8192);e.setText(df.format(map[y][x]));e.selectAll();
        new AlertDialog.Builder(this).setTitle("Edit MAP — TPS "+df.format(tps[y])+"% / "+rpms[x]+" RPM")
            .setView(e).setPositiveButton("SIMPAN",(d,w)->{
                try{map[y][x]=Math.max(50,Math.min(150,Double.parseDouble(e.getText().toString().replace(',','.'))));}
                catch(Exception ignored){}
                saveMap();updateSelection();renderTable();updateStats();
            }).setNegativeButton("Batal",null).show();
    }
    void updateStats(){
        int n=0;double min=999,max=-999,sum=0;
        for(int y=0;y<TPS_CELLS;y++)for(int x=0;x<RPM_CELLS;x++){min=Math.min(min,map[y][x]);max=Math.max(max,map[y][x]);sum+=map[y][x];n++;}
        stats.setText("MAP 21×61 = 1281 cell  •  MIN "+df.format(min)+"%  MAX "+df.format(max)+"%  AVG "+df.format(sum/n)+"%");
    }

    void chooseBluetooth(){
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=0){
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_SCAN"},90);return;
        }
        BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
        if(a==null){status.setText("Bluetooth tidak tersedia.");return;}
        Set<BluetoothDevice> ds=a.getBondedDevices();
        if(ds.isEmpty()){status.setText("Pair modem Bluetooth ECU terlebih dahulu.");return;}
        BluetoothDevice[] arr=ds.toArray(new BluetoothDevice[0]);String[] names=new String[arr.length];
        for(int i=0;i<arr.length;i++)names[i]=String.valueOf(arr[i].getName())+" — "+arr[i].getAddress();
        new AlertDialog.Builder(this).setTitle("Pilih modem ECU").setItems(names,(d,w)->connect(arr[w])).show();
    }
    void connect(BluetoothDevice d){
        new Thread(()->{
            try{
                socket=d.createRfcommSocketToServiceRecord(UUID.fromString(SPP));socket.connect();
                input=socket.getInputStream();output=socket.getOutputStream();
                runOnUiThread(()->status.setText("Bluetooth TERHUBUNG: "+d.getName()));
                rx=new Thread(this::readLoop);rx.start();
            }catch(Exception e){runOnUiThread(()->status.setText("Gagal Bluetooth: "+e.getMessage()));}
        }).start();
    }
    void readLoop(){
        byte[] buf=new byte[2048];StringBuilder sb=new StringBuilder();
        try{
            int n;while(socket!=null&&socket.isConnected()&&(n=input.read(buf))!=-1){
                String s=new String(buf,0,n,StandardCharsets.US_ASCII);sb.append(s);
                int p;while((p=sb.indexOf("\n"))>=0){String line=sb.substring(0,p).replace("\r","").trim();sb.delete(0,p+1);if(!line.isEmpty())parseTelemetry(line);}
            }
        }catch(Exception ignored){}
    }
    void parseTelemetry(String s){
        try{
            Double a=null,r=null,t=null;
            for(String p:s.split("[,; ]")){
                String[] q=p.split("=");
                if(q.length!=2)continue;
                String k=q[0].trim().toUpperCase(),v=q[1].trim();
                if(k.equals("AFR"))a=Double.valueOf(v);if(k.equals("RPM"))r=Double.valueOf(v);if(k.equals("TPS"))t=Double.valueOf(v);
            }
            if(a!=null&&r!=null&&t!=null){final double fa=a,fr=r,ft=t;runOnUiThread(()->{
                afr.setText(df.format(fa));rpm.setText(String.valueOf((int)fr));tpsIn.setText(df.format(ft));
                selectedX=cellRpm(fr);selectedY=cellTps(ft);
                live.setText("LIVE  AFR "+df.format(fa)+"  TARGET "+df.format(num(target,13.8))+"  RPM "+(int)fr+"  TPS "+df.format(ft)+"%");
                if(tuning)applySample();else{updateSelection();renderTable();}
            });}
        }catch(Exception ignored){}
    }

    String build2602(){
        int y=selectedY;StringBuilder s=new StringBuilder("2602;AUTOAFR;"+y+";");
        for(int x=0;x<RPM_CELLS;x++){if(x>0)s.append(';');s.append(df.format(map[y][x]));}
        s.append("\r\n");return s.toString();
    }
    void showPacket(){
        String p=build2602();TextView v=new TextView(this);v.setText(p);v.setTextIsSelectable(true);v.setPadding(12,12,12,12);
        new AlertDialog.Builder(this).setTitle("2602 PACKET — PREVIEW").setView(v).setPositiveButton("OK",null).show();
        status.setText("Packet 2602 siap untuk verifikasi.");
    }
    void send2602Test(){
        if(output==null){status.setText("Belum terhubung Bluetooth.");return;}
        new AlertDialog.Builder(this).setTitle("Kirim 2602?")
            .setMessage("Ini hanya TEST packet dari struktur bytecode yang ditemukan. Jangan gunakan sebagai WRITE ECU final sebelum argument/index/ACK Juken terverifikasi.")
            .setNegativeButton("Batal",null).setPositiveButton("KIRIM",(d,w)->{
                try{output.write(build2602().getBytes(StandardCharsets.US_ASCII));output.flush();status.setText("2602 TEST terkirim — belum dianggap WRITE MAP.");}
                catch(Exception e){status.setText("Kirim gagal: "+e.getMessage());}
            }).show();
    }

    void confirmReset(){
        new AlertDialog.Builder(this).setTitle("Reset seluruh MAP?")
            .setMessage("Semua 1281 cell kembali 100%.").setNegativeButton("Batal",null)
            .setPositiveButton("RESET",(d,w)->{defaultMap();saveMap();renderTable();updateSelection();updateStats();status.setText("MAP di-reset ke 100%.");}).show();
    }

    void openMap(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE),20);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==20&&resultCode==RESULT_OK&&data!=null)importCsv(data.getData());
    }
    void importCsv(Uri uri){
        try{
            BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri),StandardCharsets.UTF_8));
            String line=r.readLine();if(line==null)throw new Exception("File kosong");
            for(int y=0;y<TPS_CELLS;y++){
                line=r.readLine();if(line==null)break;String[] a=line.split(",");
                for(int x=0;x<RPM_CELLS&&x+1<a.length;x++)map[y][x]=Double.parseDouble(a[x+1].trim().replace(',','.'));
            }
            r.close();saveMap();renderTable();updateSelection();updateStats();status.setText("MAP berhasil di-import.");
        }catch(Exception e){status.setText("Import gagal: "+e.getMessage());}
    }
    void exportCsv(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"juken5_map_21x61.csv");startActivityForResult(i,21);
    }
    void saveMapToUri(Uri uri){
        try{
            OutputStream o=getContentResolver().openOutputStream(uri);StringBuilder s=new StringBuilder("TPS/RPM");
            for(int x=0;x<RPM_CELLS;x++)s.append(',').append(rpms[x]);s.append('\n');
            for(int y=0;y<TPS_CELLS;y++){s.append(df.format(tps[y]));for(int x=0;x<RPM_CELLS;x++)s.append(',').append(df.format(map[y][x]));s.append('\n');}
            o.write(s.toString().getBytes(StandardCharsets.UTF_8));o.close();status.setText("MAP CSV berhasil diekspor.");
        }catch(Exception e){status.setText("Export gagal: "+e.getMessage());}
    }
    @Override protected void onActivityResultOld(int requestCode,int resultCode,Intent data){}

    @Override protected void onDestroy(){disconnect();super.onDestroy();}
    void disconnect(){try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;input=null;output=null;tuning=false;status.setText("Bluetooth terputus.");}
}
