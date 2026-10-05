package me.hd.wauxv.data.bean.info;
public class FriendInfo {
    private final String wxid, nickname;
    public FriendInfo(String w, String n) { wxid = w; nickname = n; }
    public String getWxid() { return wxid; }
    public String getNickname() { return nickname; }
}
