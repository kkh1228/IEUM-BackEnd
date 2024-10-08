package com.goormcoder.ieum.service;

import com.goormcoder.ieum.domain.*;
import com.goormcoder.ieum.domain.enumeration.DestinationName;
import com.goormcoder.ieum.domain.enumeration.PlanVehicle;
import com.goormcoder.ieum.dto.request.PlanCreateDto;
import com.goormcoder.ieum.dto.response.DestinationFindDto;
import com.goormcoder.ieum.dto.response.PlanFindDto;
import com.goormcoder.ieum.dto.response.PlanInfoDto;
import com.goormcoder.ieum.dto.response.PlanSortDto;
import com.goormcoder.ieum.exception.ErrorMessages;
import com.goormcoder.ieum.repository.DestinationRepository;
import com.goormcoder.ieum.repository.PlanRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanRepository planRepository;
    private final DestinationRepository destinationRepository;
    private final MemberService memberService;
    private final GoogleCalendarService googleCalendarService;
    
    @Transactional
    public List<DestinationFindDto> getAllDestinations() {
        return DestinationFindDto.listOf(destinationRepository.findAll());
    }

    @Transactional
    public PlanInfoDto createPlan(PlanCreateDto dto, Member member) {
        Destination destination = destinationRepository.findById(dto.destinationId())
                .orElseThrow(() -> new EntityNotFoundException(ErrorMessages.DESTINATION_NOT_FOUND.getMessage()));
        validatePlanCreateDto(dto);

        Plan plan = Plan.of(destination, dto.startedAt(), dto.endedAt(), dto.vehicle());
        plan.addPlanMember(PlanMember.of(plan, member));

        return PlanInfoDto.of(planRepository.save(plan));

    }

    @Transactional(readOnly = true)
    public PlanFindDto getPlan(Long planId, Member member) {
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);

        return PlanFindDto.of(plan);
    }

    @Transactional(readOnly = true)
    public List<PlanSortDto> listAllPlans(UUID memberId) {
        List<Plan> plans = planRepository.findAll().stream()
                .filter(plan -> plan.getPlanMembers().stream().anyMatch(pm -> pm.getMember().getId().equals(memberId)))
                .collect(Collectors.toList());
        return PlanSortDto.listOf(plans);
    }

    @Transactional(readOnly = true)
    public List<PlanSortDto> listPlansByStartDate(UUID memberId) {
        List<Plan> plans = planRepository.findAllByOrderByStartedAtDesc().stream()
                .filter(plan -> plan.getPlanMembers().stream().anyMatch(pm -> pm.getMember().getId().equals(memberId)))
                .collect(Collectors.toList());
        return PlanSortDto.listOf(plans);
    }

    @Transactional(readOnly = true)
    public List<PlanSortDto> listPlansByDestination(UUID memberId, DestinationName destinationName) {
        List<Plan> plans = planRepository.findByDestination_DestinationNameOrderByStartedAtDesc(destinationName).stream()
                .filter(plan -> plan.getPlanMembers().stream().anyMatch(pm -> pm.getMember().getId().equals(memberId)))
                .collect(Collectors.toList());
        return PlanSortDto.listOf(plans);
    }

    @Transactional(readOnly = true)
    public List<PlanSortDto> listPlansByDestinationAndDateRange(UUID memberId, DestinationName destinationName, LocalDateTime start, LocalDateTime end) {
        List<Plan> plans = planRepository.findByDestination_DestinationNameAndStartedAtBetween(destinationName, start, end).stream()
                .filter(plan -> plan.getPlanMembers().stream().anyMatch(pm -> pm.getMember().getId().equals(memberId)))
                .collect(Collectors.toList());
        return PlanSortDto.listOf(plans);
    }

    @Transactional
    public void deletePlan(Long planId, Member member) {
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);
        plan.markAsDeleted();
        planRepository.save(plan);
    }

    @Transactional
    public PlanInfoDto updatePlan(Long planId, PlanCreateDto dto, UUID memberId) {
        Member member = memberService.findById(memberId);
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);

        Destination destination = destinationRepository.findById(dto.destinationId())
                .orElseThrow(() -> new EntityNotFoundException(ErrorMessages.DESTINATION_NOT_FOUND.getMessage()));
        validatePlanCreateDto(dto);

        plan.update(destination, dto.startedAt(), dto.endedAt(), dto.vehicle());
        resetInvalidPlaceTimes(plan);

        return PlanInfoDto.of(planRepository.save(plan));
    }

    @Transactional
    public PlanInfoDto updateDestination(Long planId, Long newDestinationId, UUID memberId) {
        Member member = memberService.findById(memberId);
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);

        Destination newDestination = destinationRepository.findById(newDestinationId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorMessages.DESTINATION_NOT_FOUND.getMessage()));

        plan.update(newDestination, plan.getStartedAt(), plan.getEndedAt(), plan.getVehicle());

        return PlanInfoDto.of(planRepository.save(plan));
    }

    @Transactional
    public PlanInfoDto updateStartTime(Long planId, LocalDateTime newStartTime, UUID memberId) {
        Member member = memberService.findById(memberId);
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);

        validateStartEndTime(newStartTime, plan.getEndedAt());

        plan.update(plan.getDestination(), newStartTime, plan.getEndedAt(), plan.getVehicle());
        resetInvalidPlaceTimes(plan);

        return PlanInfoDto.of(planRepository.save(plan));
    }

    @Transactional
    public PlanInfoDto updateEndTime(Long planId, LocalDateTime newEndTime, UUID memberId) {
        Member member = memberService.findById(memberId);
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);

        validateStartEndTime(plan.getStartedAt(), newEndTime);

        plan.update(plan.getDestination(), plan.getStartedAt(), newEndTime, plan.getVehicle());
        resetInvalidPlaceTimes(plan);

        return PlanInfoDto.of(planRepository.save(plan));
    }

    @Transactional
    public PlanInfoDto updateVehicle(Long planId, PlanVehicle newVehicle, UUID memberId) {
        Member member = memberService.findById(memberId);
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);

        plan.update(plan.getDestination(), plan.getStartedAt(), plan.getEndedAt(), newVehicle);

        return PlanInfoDto.of(planRepository.save(plan));
    }

    public Plan findByPlanId(Long planId) {
        return planRepository.findByIdAndDeletedAtIsNull(planId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorMessages.PLAN_NOT_FOUND.getMessage()));
    }

    public void validatePlanMember(Plan plan, Member member) {
        plan.getPlanMembers().stream()
                .filter(planMember -> planMember.getMember().getId().equals(member.getId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(ErrorMessages.PLAN_MEMBER_NOT_FOUND.getMessage()));
    }

    private void validatePlanCreateDto(PlanCreateDto dto) {
        LocalDate start = dto.startedAt().toLocalDate();
        LocalDate end = dto.endedAt().toLocalDate();

        if(start.isAfter(end)) {
            throw new IllegalArgumentException(ErrorMessages.BAD_REQUEST_PLAN_VISIT_START_TIME.getMessage());
        }
    }

    private void validateStartEndTime(LocalDateTime start, LocalDateTime end) {
        if (start.isAfter(end) || start.isEqual(end)) {
            throw new IllegalArgumentException(ErrorMessages.BAD_REQUEST_PLACE_VISIT_START_TIME.getMessage());
        }
    }

    private void resetInvalidPlaceTimes(Plan plan) {
        for (Place place : plan.getPlaces()) {
            if (place.getStartedAt().isBefore(plan.getStartedAt()) || place.getEndedAt().isAfter(plan.getEndedAt())) {
                place.resetVisitTimes();
            }
        }

    }

    @Transactional
    public void finalizePlan(Long planId, UUID memberId) {
        Member member = memberService.findById(memberId);
        Plan plan = findByPlanId(planId);
        validatePlanMember(plan, member);

        try {
            googleCalendarService.createGoogleCalendarEvent(plan);
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            //
        }
    }
}
