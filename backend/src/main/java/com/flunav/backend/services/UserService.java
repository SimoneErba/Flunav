package com.flunav.backend.services;

import com.flunav.backend.domain.Role;
import com.flunav.backend.domain.User;
import com.flunav.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final OrientDBService orientDBService;
    private final PasswordEncoder passwordEncoder;

    @Value("${superadmin.username}")
    private String superAdminUsername;

    @Value("${superadmin.password}")
    private String superAdminPassword;

    public UserService(OrientDBService orientDBService, PasswordEncoder passwordEncoder) {
        this.orientDBService = orientDBService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void initSuperAdmin() {
        try {
            if (getUserByUsername(superAdminUsername).isEmpty()) {
                createUser(new User(superAdminUsername, superAdminPassword, Role.SUPERADMIN));
                logger.info("Superadmin user created.");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize superadmin", e);
        }
    }

    public User createUser(User user) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            if (checkIfUserExists(db, user.getUsername())) {
                throw new IllegalArgumentException("User " + user.getUsername() + " already exists.");
            }

            OVertex userVertex = db.newVertex("User");
            userVertex.setProperty("username", user.getUsername());
            userVertex.setProperty("password", passwordEncoder.encode(user.getPassword()));
            userVertex.setProperty("role", user.getRole().name());
            userVertex.save();

            return user;
        } catch (Exception e) {
            throw new RuntimeException("Error creating user " + user.getUsername(), e);
        }
    }

    public Optional<User> getUserByUsername(String username) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            String query = "SELECT FROM User WHERE username = ?";
            try (OResultSet rs = db.query(query, username)) {
                if (rs.hasNext()) {
                    OResult item = rs.next();
                    return item.getVertex().map(this::vertexToUser);
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching user " + username, e);
        }
        return Optional.empty();
    }
    
    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        try (ODatabaseSession db = orientDBService.getSession()) {
            String query = "SELECT FROM User";
            try (OResultSet rs = db.query(query)) {
                while (rs.hasNext()) {
                    OResult item = rs.next();
                    item.getVertex().ifPresent(v -> users.add(vertexToUser(v)));
                }
            }
        } catch (Exception e) {
             throw new RuntimeException("Error fetching users", e);
        }
        return users;
    }
    
    public void deleteUser(String username) {
         try (ODatabaseSession db = orientDBService.getSession()) {
            String query = "DELETE VERTEX User WHERE username = ?";
            db.command(query, username);
        } catch (Exception e) {
            throw new RuntimeException("Error deleting user " + username, e);
        }
    }

    private boolean checkIfUserExists(ODatabaseSession db, String username) {
        String query = "SELECT FROM User WHERE username = ?";
        try (OResultSet rs = db.query(query, username)) {
            return rs.hasNext();
        }
    }

    private User vertexToUser(OVertex vertex) {
        return new User(
                vertex.getProperty("username"),
                vertex.getProperty("password"),
                Role.valueOf(vertex.getProperty("role")));
    }
}