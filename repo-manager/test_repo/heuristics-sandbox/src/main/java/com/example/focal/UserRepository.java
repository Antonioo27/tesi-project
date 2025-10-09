package com.example.focal;

public interface UserRepository {
    boolean exists(String email);

    void save(String email);
}
