package com.example.authservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

// @Entity tells jpa/hibernate this class maps to a database table, @Table pins the exact table name
// lombok's @Getter/@Setter generate all the boilerplate getter and setter methods at compile time,
// so they never actually show up in this source file even though other classes call them
// implementing userdetails is what lets spring security treat this entity as the logged in principal
@Entity
@Table(name = "users")
@Getter
@Setter
public class User implements UserDetails {

    // generationtype.identity means the database itself assigns the id on insert (like postgres
    // serial/identity columns), as opposed to sequence or table strategies which ask the db for
    // the next id value up front before the row is even inserted
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    // Unique for the same reason as email: this is the destination for 2FA codes, so two accounts
    // sharing a number means one person's phone can complete the other person's login. Nullable,
    // and Postgres allows any number of nulls under a unique constraint, so rows with no number
    // on file are unaffected.
    @Column(unique = true)
    private String phoneNumber;

    // Where notification-service sends balance summaries and transaction alerts. Nullable rather
    // than required, because users registered before this field existed have none - the notification
    // listeners skip a user with no address instead of inventing one.
    @Column(unique = true)
    private String email;

    private Boolean totpEnabled = false;
    private String totpSecret;

    // these five overrides come from the userdetails interface, spring security calls them to
    // decide whether a login attempt is even allowed to succeed, all hardcoded true/empty here
    // since this project is not using account lockout or role based authorities yet
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}