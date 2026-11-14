package com.boongboongus.backend.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.LocalDate;

public class ProfilePatch {
    private boolean nicknamePresent;
    private String nickname;
    private boolean birthDatePresent;
    private LocalDate birthDate;

    @JsonSetter
    public void setNickname(String nickname) {
        this.nicknamePresent = true;
        this.nickname = nickname;
    }

    @JsonSetter
    public void setBirthDate(LocalDate birthDate) {
        this.birthDatePresent = true;
        this.birthDate = birthDate;
    }

    public boolean nicknamePresent() { return nicknamePresent; }
    public String nickname() { return nickname; }
    public boolean birthDatePresent() { return birthDatePresent; }
    public LocalDate birthDate() { return birthDate; }

    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown profile field");
    }
}
