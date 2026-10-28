package com.enterprise.chat.domain.entity;

public enum UserRole {
  MEMBER(10),
  MANAGER(20),
  ADMIN(30),
  COMPLIANCE(40);
  private final int rank;

  UserRole(int rank) {
    this.rank = rank;
  }

  public int rank() {
    return rank;
  }
}
