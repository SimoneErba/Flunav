package com.fiumen.backend.domain;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProgressInfo {
    private Double progress;
    private Instant datetime;
}