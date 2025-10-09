package com.example.focal;

import java.util.LinkedHashSet;
import java.util.Set;

public class SimpleUserRepository implements UserRepository {
    private final Set<String> emails = new LinkedHashSet<>();

    @Override
    public boolean exists(String email) {
        return emails.contains(email);
    }

    @Override
    public void save(String email) {
        emails.add(email);
    }
}
