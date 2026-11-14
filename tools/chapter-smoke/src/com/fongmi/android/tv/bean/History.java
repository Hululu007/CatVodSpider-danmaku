package com.fongmi.android.tv.bean;
import java.util.*;
public class History {
 public static final List<History> records=new ArrayList<>();
 public static boolean failEnumeration,failWrite;
 private String key; private int cid=1; private long opening,ending;
 public History(String key,long opening,long ending){this.key=key;this.opening=opening;this.ending=ending;records.add(this);}
 public String getKey(){return key;} public int getCid(){return cid;}
 public long getOpening(){return opening;}public long getEnding(){return ending;}
 public void setOpening(long v){if(failWrite)throw new IllegalStateException("write fail");opening=v;}
 public void setEnding(long v){if(failWrite)throw new IllegalStateException("write fail");ending=v;}
 public History save(){if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new AssertionError("History accessed off main");return this;}
 public static List<History> get(){if(failEnumeration)throw new IllegalStateException("enumeration fail");return new ArrayList<>(records);}
}