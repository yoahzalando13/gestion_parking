package com.example.parking.service.impl;

import com.example.parking.config.ParkingProperties;
import com.example.parking.dto.response.InvoiceResponse;
import com.example.parking.entity.Invoice;
import com.example.parking.entity.ParkingSession;
import com.example.parking.entity.Subscription;
import com.example.parking.entity.TariffRule;
import com.example.parking.entity.enums.InvoiceStatus;
import com.example.parking.exception.BusinessException;
import com.example.parking.exception.InvalidOperationException;
import com.example.parking.exception.ResourceNotFoundException;
import com.example.parking.mapper.InvoiceMapper;
import com.example.parking.repository.InvoiceRepository;
import com.example.parking.repository.specification.InvoiceSpecifications;
import com.example.parking.service.BillingService;
import com.example.parking.service.TariffService;
import com.example.parking.util.PricingCalculator;
import com.example.parking.util.PricingResult;
import com.example.parking.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional(readOnly = true)
public class BillingServiceImpl implements BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingServiceImpl.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceMapper invoiceMapper;
    private final TariffService tariffService;
    private final PricingCalculator pricingCalculator;
    private final ParkingProperties properties;

    public BillingServiceImpl(InvoiceRepository invoiceRepository,
                              InvoiceMapper invoiceMapper,
                              TariffService tariffService,
                              PricingCalculator pricingCalculator,
                              ParkingProperties properties) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceMapper = invoiceMapper;
        this.tariffService = tariffService;
        this.pricingCalculator = pricingCalculator;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Invoice issueInvoice(ParkingSession session, Subscription subscription, boolean markAsPaid) {
        if (session.getExitTime() == null) {
            throw new BusinessException("La session n'est pas terminee : facturation impossible");
        }
        if (invoiceRepository.findBySessionId(session.getId()).isPresent()) {
            throw new BusinessException("Une facture existe deja pour la session " + session.getReference());
        }

        long actualMinutes = session.getElapsedMinutes(session.getExitTime());
        long expectedMinutes = session.getReservation() != null
                ? session.getReservation().getExpectedDurationMinutes()
                : 0L;

        TariffRule rule = tariffService.findApplicable(
                session.getSpace().getZone(),
                session.getSpace().getType(),
                session.getVehicle().getType(),
                session.getUser().getCategory());

        boolean subscriberValid = subscription != null
                && subscription.isValidOn(session.getExitTime().toLocalDate());

        PricingResult result = pricingCalculator.compute(
                actualMinutes,
                expectedMinutes,
                rule,
                session.getSpace().getType(),
                session.getVehicle().getType(),
                session.getUser().getCategory(),
                subscriberValid);

        Invoice invoice = new Invoice();
        invoice.setReference(ReferenceGenerator.invoice());
        invoice.setSession(session);
        invoice.setUser(session.getUser());
        invoice.setBilledMinutes(result.billedMinutes());
        invoice.setExpectedMinutes(result.expectedMinutes());
        invoice.setOverstayMinutes(result.overstayMinutes());
        invoice.setBaseAmount(result.baseAmount());
        invoice.setOverstayAmount(result.overstayAmount());
        invoice.setDiscountAmount(result.discountAmount());
        invoice.setTotalAmount(result.totalAmount());
        invoice.setAppliedTariff(result.appliedTariff());
        invoice.setDetails(result.details());
        invoice.setIssuedAt(LocalDateTime.now());
        if (markAsPaid) {
            invoice.setStatus(InvoiceStatus.PAYEE);
            invoice.setPaidAt(LocalDateTime.now());
        } else {
            invoice.setStatus(InvoiceStatus.EN_ATTENTE);
        }

        Invoice saved = invoiceRepository.save(invoice);
        log.info("Facture {} emise pour la session {} : {} {}", saved.getReference(), session.getReference(),
                saved.getTotalAmount(), properties.getPricing().getCurrency());
        return saved;
    }

    @Override
    public Page<InvoiceResponse> search(String term, InvoiceStatus status, LocalDateTime from, LocalDateTime to,
                                        Pageable pageable) {
        Specification<Invoice> spec = Specification.where(InvoiceSpecifications.search(term))
                .and(InvoiceSpecifications.hasStatus(status))
                .and(InvoiceSpecifications.issuedFrom(from))
                .and(InvoiceSpecifications.issuedTo(to));
        return invoiceRepository.findAll(spec, pageable).map(invoiceMapper::toResponse);
    }

    @Override
    public InvoiceResponse getById(Long id) {
        return invoiceMapper.toResponse(getEntity(id));
    }

    @Override
    @Transactional
    public InvoiceResponse markAsPaid(Long id) {
        Invoice invoice = getEntity(id);
        if (invoice.getStatus() == InvoiceStatus.PAYEE) {
            throw new InvalidOperationException("Cette facture est deja reglee");
        }
        if (invoice.getStatus() == InvoiceStatus.ANNULEE) {
            throw new InvalidOperationException("Une facture annulee ne peut pas etre reglee");
        }
        invoice.setStatus(InvoiceStatus.PAYEE);
        invoice.setPaidAt(LocalDateTime.now());
        return invoiceMapper.toResponse(invoiceRepository.save(invoice));
    }

    @Override
    @Transactional
    public InvoiceResponse cancel(Long id) {
        Invoice invoice = getEntity(id);
        if (invoice.getStatus() == InvoiceStatus.PAYEE) {
            throw new InvalidOperationException("Une facture reglee ne peut pas etre annulee");
        }
        invoice.setStatus(InvoiceStatus.ANNULEE);
        return invoiceMapper.toResponse(invoiceRepository.save(invoice));
    }

    @Override
    public String currency() {
        return properties.getPricing().getCurrency();
    }

    private Invoice getEntity(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facture", id));
    }
}
