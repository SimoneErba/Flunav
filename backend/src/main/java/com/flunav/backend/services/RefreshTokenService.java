package com.flunav.backend.services;

import com.flunav.backend.domain.RefreshToken;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.OElement;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenService.class);

    @Value("${jwt.refreshExpirationMs:86400000}") // Default 24h
    private Long refreshTokenDurationMs;

    private final OrientDBService orientDBService;

    public RefreshTokenService(OrientDBService orientDBService) {
        this.orientDBService = orientDBService;
    }

    @PostConstruct
    public void init() {
        try (ODatabaseSession db = orientDBService.getSession()) {
            if (db != null && db.getClass("RefreshToken") == null) {
                OClass refreshTokenClass = db.createClass("RefreshToken");
                refreshTokenClass.createProperty("token", OType.STRING);
                refreshTokenClass.createProperty("username", OType.STRING);
                refreshTokenClass.createProperty("expiryDate", OType.DATETIME);
                refreshTokenClass.createIndex("RefreshToken.token", OClass.INDEX_TYPE.UNIQUE, "token");
                logger.info("RefreshToken class created in OrientDB.");
            }
        } catch (Exception e) {
            logger.error("Error initializing RefreshToken class", e);
        }
    }

    public RefreshToken createRefreshToken(String username) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUsername(username);
        refreshToken.setExpiryDate(Instant.now().plusMillis(refreshTokenDurationMs));
        refreshToken.setToken(UUID.randomUUID().toString());

        try (ODatabaseSession db = orientDBService.getSession()) {
            // Optional: limit 1 token per user -> delete old ones
            String deleteQuery = "DELETE FROM RefreshToken WHERE username = ?";
            db.command(deleteQuery, username);

            OElement tokenDoc = db.newInstance("RefreshToken");
            tokenDoc.setProperty("token", refreshToken.getToken());
            tokenDoc.setProperty("username", refreshToken.getUsername());
            tokenDoc.setProperty("expiryDate", java.util.Date.from(refreshToken.getExpiryDate()));
            tokenDoc.save();
        }

        return refreshToken;
    }

    public Optional<RefreshToken> findByToken(String token) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            String query = "SELECT FROM RefreshToken WHERE token = ?";
            try (OResultSet rs = db.query(query, token)) {
                if (rs.hasNext()) {
                    OResult item = rs.next();
                    return item.getElement().map(this::elementToRefreshToken);
                }
            }
        }
        return Optional.empty();
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            deleteByToken(token.getToken());
            throw new RuntimeException("Refresh token was expired. Please make a new signin request");
        }
        return token;
    }

    public void deleteByUsername(String username) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            String query = "DELETE FROM RefreshToken WHERE username = ?";
            db.command(query, username);
        }
    }

    public void deleteByToken(String token) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            String query = "DELETE FROM RefreshToken WHERE token = ?";
            db.command(query, token);
        }
    }

    private RefreshToken elementToRefreshToken(OElement element) {
        java.util.Date date = element.getProperty("expiryDate");
        return new RefreshToken(
                element.getProperty("token"),
                element.getProperty("username"),
                date.toInstant());
    }
}
