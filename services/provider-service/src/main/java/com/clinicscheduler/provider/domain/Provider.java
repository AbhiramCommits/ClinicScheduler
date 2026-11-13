package com.clinicscheduler.provider.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "providers")
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "specialty", nullable = false, length = 80)
    private String specialty;

    @Column(name = "npi_number", nullable = false, unique = true, length = 10)
    private String npiNumber;

    @Column(name = "clinic_location", nullable = false, length = 120)
    private String clinicLocation;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Provider() {
    }

    public Provider(String fullName, String specialty, String npiNumber, String clinicLocation, boolean active) {
        this.fullName = fullName;
        this.specialty = specialty;
        this.npiNumber = npiNumber;
        this.clinicLocation = clinicLocation;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getSpecialty() {
        return specialty;
    }

    public String getNpiNumber() {
        return npiNumber;
    }

    public String getClinicLocation() {
        return clinicLocation;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
