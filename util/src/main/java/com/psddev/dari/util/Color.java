package com.psddev.dari.util;

/**
 * Color object
 */
public class Color {

    private String hex;

    public Color(String hex) {
        this.hex = hex;
    }

    public String getHex() {
        return hex;
    }

    public void setHex(String hex) {
        this.hex = hex;
    }
    
    public int[] getRGB(){
        return toRGB(this.hex);
    }
    
    public static int[] toRGB(String hex) {
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            rgb[i] = hexToInt(hex.charAt(i * 2), hex.charAt(i * 2 + 1));
        }
        return rgb;
    }

    public static int hexToInt(char a, char b) {
        int x = a < 65 ? a - 48 : a - 55;
        int y = b < 65 ? b - 48 : b - 55;
        return x * 16 + y;
    }
}
