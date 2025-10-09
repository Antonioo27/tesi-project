package com.example.focal;

public class ReportGenerator {
    private final EmailSender email;

    public ReportGenerator(EmailSender email) {
        this.email = email;
    }

    public boolean sendReport(String to, String body) {
        String formatted = StaticUtil.banner(body);
        return email.send(to, formatted);
    }
}
