/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.v2.user;

import alfio.controller.EventController;
import alfio.controller.api.v2.model.*;
import alfio.controller.api.v2.model.EventWithAdditionalInfo.PaymentProxyWithParameters;
import alfio.controller.decorator.EventDescriptor;
import alfio.controller.decorator.SaleableAdditionalService;
import alfio.controller.decorator.SaleableTicketCategory;
import alfio.controller.form.ReservationForm;
import alfio.controller.support.Formatters;
import alfio.manager.EuVatChecker;
import alfio.manager.EventManager;
import alfio.manager.PaymentManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.system.ConfigurationManager;
import alfio.model.AdditionalServiceText;
import alfio.model.Event;
import alfio.model.EventDescription;
import alfio.model.modification.support.LocationDescriptor;
import alfio.model.result.ValidationResult;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.PaymentMethod;
import alfio.model.transaction.PaymentProxy;
import alfio.repository.*;
import alfio.repository.user.OrganizationRepository;
import alfio.util.CustomResourceBundleMessageSource;
import alfio.util.MustacheCustomTagInterceptor;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static alfio.model.system.ConfigurationKeys.*;
import static java.util.stream.Collectors.toList;


@RestController
@RequestMapping("/api/v2/public/")
@AllArgsConstructor
public class EventApiV2Controller {

    private final EventController eventController;
    private final EventManager eventManager;
    private final EventRepository eventRepository;
    private final ConfigurationManager configurationManager;
    private final OrganizationRepository organizationRepository;
    private final EventDescriptionRepository eventDescriptionRepository;
    private final TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository;
    private final PaymentManager paymentManager;
    private final CustomResourceBundleMessageSource messageSource;
    private final EuVatChecker vatChecker;
    private final AdditionalServiceRepository additionalServiceRepository;
    private final AdditionalServiceTextRepository additionalServiceTextRepository;


    @GetMapping("events")
    public ResponseEntity<List<BasicEventInfo>> listEvents() {
        var events = eventManager.getPublishedEvents()
            .stream()
            .map(e -> new BasicEventInfo(e.getShortName(), e.getFileBlobId(), e.getDisplayName(), e.getLocation()))
            .collect(Collectors.toList());
        return new ResponseEntity<>(events, getCorsHeaders(), HttpStatus.OK);
    }

    @GetMapping("event/{eventName}")
    public ResponseEntity<EventWithAdditionalInfo> getEvent(@PathVariable("eventName") String eventName) {
        return eventRepository.findOptionalByShortName(eventName).filter(e -> e.getStatus() != Event.Status.DISABLED)//
            .map(event -> {

                var descriptions = applyCommonMark(eventDescriptionRepository.findByEventIdAndType(event.getId(), EventDescription.EventDescriptionType.DESCRIPTION)
                    .stream()
                    .collect(Collectors.toMap(EventDescription::getLocale, EventDescription::getDescription)));

                var organization = organizationRepository.getById(event.getOrganizationId());

                Map<ConfigurationKeys, Optional<String>> geoInfoConfiguration = configurationManager.getStringConfigValueFrom(
                    Configuration.from(event, ConfigurationKeys.MAPS_PROVIDER),
                    Configuration.from(event, ConfigurationKeys.MAPS_CLIENT_API_KEY),
                    Configuration.from(event, ConfigurationKeys.MAPS_HERE_APP_ID),
                    Configuration.from(event, ConfigurationKeys.MAPS_HERE_APP_CODE));

                var ld = LocationDescriptor.fromGeoData(event.getLatLong(), TimeZone.getTimeZone(event.getTimeZone()), geoInfoConfiguration);

                Map<PaymentMethod, PaymentProxyWithParameters> availablePaymentMethods = new EnumMap<>(PaymentMethod.class);

                var activePaymentMethods = getActivePaymentMethods(event);

                activePaymentMethods.forEach(apm -> {
                    availablePaymentMethods.put(apm.getPaymentMethod(), new PaymentProxyWithParameters(apm, paymentManager.loadModelOptionsFor(Collections.singletonList(apm), event)));
                });


                //
                String bankAccount = configurationManager.getStringConfigValue(Configuration.from(event, BANK_ACCOUNT_NR)).orElse("");
                List<String> bankAccountOwner = Arrays.asList(configurationManager.getStringConfigValue(Configuration.from(event, BANK_ACCOUNT_OWNER)).orElse("").split("\n"));
                boolean canGenerateReceiptOrInvoiceToCustomer = configurationManager.canGenerateReceiptOrInvoiceToCustomer(event);
                //

                var formattedBeginDate = Formatters.getFormattedDate(event, event.getBegin(), "common.event.date-format", messageSource);
                var formattedBeginTime = Formatters.getFormattedDate(event, event.getBegin(), "common.event.time-format", messageSource);
                var formattedEndDate = Formatters.getFormattedDate(event, event.getEnd(), "common.event.date-format", messageSource);
                var formattedEndTime = Formatters.getFormattedDate(event, event.getEnd(), "common.event.time-format", messageSource);


                var partialConfig = Configuration.from(event);

                //invoicing information
                boolean euVatCheckingEnabled = vatChecker.isReverseChargeEnabledFor(event.getOrganizationId());
                boolean invoiceAllowed = configurationManager.hasAllConfigurationsForInvoice(event) || euVatCheckingEnabled;
                boolean onlyInvoice = invoiceAllowed && configurationManager.isInvoiceOnly(event);
                boolean customerReferenceEnabled = configurationManager.getBooleanConfigValue(partialConfig.apply(ENABLE_CUSTOMER_REFERENCE), false);
                boolean enabledItalyEInvoicing = configurationManager.getBooleanConfigValue(partialConfig.apply(ENABLE_ITALY_E_INVOICING), false);
                boolean vatNumberStrictlyRequired = configurationManager.getBooleanConfigValue(partialConfig.apply(VAT_NUMBER_IS_REQUIRED), false);

                var invoicingConf = new EventWithAdditionalInfo.InvoicingConfiguration(euVatCheckingEnabled, invoiceAllowed, onlyInvoice,
                    customerReferenceEnabled, enabledItalyEInvoicing, vatNumberStrictlyRequired);
                //

                return new ResponseEntity<>(new EventWithAdditionalInfo(event, ld.getMapUrl(), organization, descriptions, availablePaymentMethods,
                    canGenerateReceiptOrInvoiceToCustomer, bankAccount, bankAccountOwner,
                    formattedBeginDate, formattedBeginTime,
                    formattedEndDate, formattedEndTime, invoicingConf), getCorsHeaders(), HttpStatus.OK);
            })
            .orElseGet(() -> ResponseEntity.notFound().headers(getCorsHeaders()).build());
    }

    private List<PaymentProxy> getActivePaymentMethods(Event event) {
        if(!event.isFreeOfCharge()) {
            return paymentManager.getPaymentMethods(event)
                .stream()
                .filter(p -> TicketReservationManager.isValidPaymentMethod(p, event, configurationManager))
                .map(PaymentManager.PaymentMethodDTO::getPaymentProxy)
                .collect(toList());
        } else {
            return Collections.emptyList();
        }
    }

    private static Map<String, String> applyCommonMark(Map<String, String> in) {
        if (in == null) {
            return Collections.emptyMap();
        }

        var res = new HashMap<String, String>();
        in.forEach((k, v) -> {
            res.put(k, MustacheCustomTagInterceptor.renderToCommonmark(v));
        });
        return res;
    }

    @GetMapping("event/{eventName}/ticket-categories")
    public ResponseEntity<ItemsByCategory> getTicketCategories(@PathVariable("eventName") String eventName, Model model, HttpServletRequest request) {
        if ("/event/show-event".equals(eventController.showEvent(eventName, model, request, Locale.ENGLISH))) {
            var valid = (List<SaleableTicketCategory>) model.asMap().get("ticketCategories");
            var ticketCategoryIds = valid.stream().map(SaleableTicketCategory::getId).collect(Collectors.toList());
            var ticketCategoryDescriptions = ticketCategoryDescriptionRepository.descriptionsByTicketCategory(ticketCategoryIds);
            Event event = ((EventDescriptor) model.asMap().get("event")).getEvent();

            var converted = valid.stream()
                .map(stc -> {
                    var description = applyCommonMark(ticketCategoryDescriptions.getOrDefault(stc.getId(), Collections.emptyMap()));
                    var expiration = Formatters.getFormattedDate(event, stc.getZonedExpiration(), "common.ticket-category.date-format", messageSource);
                    var inception = Formatters.getFormattedDate(event, stc.getZonedInception(), "common.ticket-category.date-format", messageSource);
                    return new TicketCategory(stc, description, inception, expiration);
                })
                .collect(Collectors.toList());


            //
            var saleableAdditionalServices = additionalServiceRepository.loadAllForEvent(event.getId())
                .stream()
                .map(as -> new SaleableAdditionalService(event, as, null, null, null, 0))
                .filter(SaleableAdditionalService::isNotExpired)
                .collect(Collectors.toList());

            // will be used for fetching descriptions and titles for all the languages
            var saleableAdditionalServicesIds = saleableAdditionalServices.stream().map(as -> as.getId()).collect(Collectors.toList());

            var additionalServiceTexts = additionalServiceTextRepository.getDescriptionsByAdditionalServiceIds(saleableAdditionalServicesIds);

            var additionalServicesRes = saleableAdditionalServices.stream().map(as -> {
                var expiration = Formatters.getFormattedDate(event, as.getZonedExpiration(), "common.ticket-category.date-format", messageSource);
                var inception = Formatters.getFormattedDate(event, as.getZonedInception(), "common.ticket-category.date-format", messageSource);
                var title = additionalServiceTexts.getOrDefault(as.getId(), Collections.emptyMap()).getOrDefault(AdditionalServiceText.TextType.TITLE, Collections.emptyMap());
                var description = applyCommonMark(additionalServiceTexts.getOrDefault(as.getId(), Collections.emptyMap()).getOrDefault(AdditionalServiceText.TextType.DESCRIPTION, Collections.emptyMap()));
                return new AdditionalService(as.getId(), as.getType(), as.getSupplementPolicy(),
                    as.isFixPrice(), as.getAvailableQuantity(), as.getMaxQtyPerOrder(),
                    as.getFree(), as.getFormattedFinalPrice(), as.getVatApplies(), as.getVatIncluded(),
                    as.isExpired(), as.getSaleInFuture(),
                    inception, expiration, title, description);
            }).collect(Collectors.toList());
            //


            return new ResponseEntity<>(new ItemsByCategory(converted, additionalServicesRes), getCorsHeaders(), HttpStatus.OK);
        } else {
            return ResponseEntity.notFound().headers(getCorsHeaders()).build();
        }
    }

    @GetMapping("event/{eventName}/calendar/{locale}")
    public void getCalendar(@PathVariable("eventName") String eventName,
                            @PathVariable("locale") String locale,
                            @RequestParam(value = "type", required = false) String calendarType,
                            @RequestParam(value = "ticketId", required = false) String ticketId,
                            HttpServletResponse response) throws IOException {
        eventController.calendar(eventName, locale, calendarType, ticketId, response);
    }

    @PostMapping("tmp/event/{eventName}/promoCode/{promoCode}")
    @ResponseBody
    public ValidationResult savePromoCode(@PathVariable("eventName") String eventName,
                                          @PathVariable("promoCode") String promoCode,
                                          Model model,
                                          HttpServletRequest request) {
        return eventController.savePromoCode(eventName, promoCode, model, request);
    }

    @PostMapping(value = "event/{eventName}/reserve-tickets")
    public ResponseEntity<ValidatedResponse<String>> reserveTicket(@PathVariable("eventName") String eventName,
                                                                   @RequestBody ReservationForm reservation,
                                                                   BindingResult bindingResult,
                                                                   ServletWebRequest request,
                                                                   RedirectAttributes redirectAttributes,
                                                                   Locale locale) {

        String redirectResult = eventController.reserveTicket(eventName, reservation, bindingResult, request, redirectAttributes, locale);

        if (bindingResult.hasErrors()) {
            return new ResponseEntity<>(ValidatedResponse.toResponse(bindingResult, null), getCorsHeaders(), HttpStatus.UNPROCESSABLE_ENTITY);
        } else {
            String reservationIdentifier = redirectResult
                .substring(redirectResult.lastIndexOf("reservation/")+"reservation/".length())
                .replace("/book", "");
            return ResponseEntity.ok(new ValidatedResponse<>(ValidationResult.success(), reservationIdentifier));
        }

    }


    private static HttpHeaders getCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        return headers;
    }
}
