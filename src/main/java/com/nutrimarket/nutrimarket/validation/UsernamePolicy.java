package com.nutrimarket.nutrimarket.validation;



public final class UsernamePolicy {
    public static final String REGEX="^[a-z][a-z0-9_]{2,31}$";
    private static final java.util.regex.Pattern PATTERN=java.util.regex.Pattern.compile(REGEX);
    private UsernamePolicy() {}
    public static boolean isValid(String username) { return username!=null && PATTERN.matcher(username).matches(); }
}
