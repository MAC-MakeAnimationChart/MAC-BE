package com.macstudio.backend.member.repository;

import com.macstudio.backend.member.entity.MemberConsent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberConsentRepository
extends JpaRepository<MemberConsent, Long> {
}
