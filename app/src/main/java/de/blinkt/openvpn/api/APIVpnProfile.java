package de.blinkt.openvpn.api;
import android.os.Parcel;
import android.os.Parcelable;
/** Parcel layout compatible with OpenVPN for Android's remoteExample. */
public final class APIVpnProfile implements Parcelable {
  public final String mUUID, mName;
  public final boolean mUserEditable;
  public APIVpnProfile(Parcel in) {
    mUUID=in.readString(); mName=in.readString(); mUserEditable=in.readInt()!=0;
  }
  public APIVpnProfile(String uuid,String name,boolean editable) {
    mUUID=uuid; mName=name; mUserEditable=editable;
  }
  @Override public int describeContents(){return 0;}
  @Override public void writeToParcel(Parcel out,int flags){
    out.writeString(mUUID);out.writeString(mName);out.writeInt(mUserEditable?1:0);
  }
  public static final Creator<APIVpnProfile> CREATOR = new Creator<APIVpnProfile>(){
    @Override public APIVpnProfile createFromParcel(Parcel p){ return new APIVpnProfile(p);}
    @Override public APIVpnProfile[] newArray(int n){return new APIVpnProfile[n];}
  };
}
