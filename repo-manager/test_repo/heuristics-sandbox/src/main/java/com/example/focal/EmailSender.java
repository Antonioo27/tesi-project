package com.example.focal;

public class EmailSender {
    public String format(String name) {
        return "Hi " + name;
    }

    public boolean send(String to, String msg) {
        return to != null && !to.isBlank() && msg != null;
    }
}
