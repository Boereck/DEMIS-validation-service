/*
 * Copyright [2023], gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 *
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either expressed or implied.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 */

package de.gematik.demis.validationservice.services.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import de.gematik.demis.validationservice.services.FhirContextService;
import de.gematik.demis.validationservice.services.ProfileParserService;
import de.gematik.demis.validationservice.services.validation.severity.SeverityParser;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.MetadataResource;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.r4.model.ValueSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service holds a Fhir validator with the given profile. */
@Service
@Slf4j
public class ValidationService {

  static final int severityOrder(ResultSeverityEnum severity) {
    if (severity == null) {
      return 0;
    }
    return switch (severity) {
      case INFORMATION -> 0;
      case WARNING -> 1;
      case ERROR -> 2;
      case FATAL -> 3;
    };
  }

  private static final Set<String> FILTERED_MESSAGES_KEYS =
      Set.of(
          "Reference_REF_CantMatchChoice",
          "BUNDLE_BUNDLE_ENTRY_MULTIPLE_PROFILES",
          "Validation_VAL_Profile_NoMatch",
          "This_element_does_not_match_any_known_slice_");
  private final FhirContextService fhirContextService;
  private final FhirValidator validator;
  private final int minSeverityOutcome;
  private Set<String> filteredMessagePrefixes;

  public ValidationService(
      FhirContextService fhirContextService,
      ProfileParserService profileParserService,
      @Value("${demis.validation-service.minSeverityOutcome}") String minSeverityOutcome) {
    this.fhirContextService = fhirContextService;
    ResultSeverityEnum minSeverity = SeverityParser.parse(minSeverityOutcome);
    if (minSeverity == null) {
      String errorMessage =
          "Configured minSeverityOutcome has an illegal value: %s".formatted(minSeverityOutcome);
      log.error(errorMessage);
      throw new NoSuchElementException(
          errorMessage); // Shutdown service -> No silent config error treatment
    }
    this.minSeverityOutcome = severityOrder(minSeverity);
    Locale parsedLocale = fhirContextService.getConfiguredLocale();
    Locale oldLocale = Locale.getDefault();
    Locale.setDefault(parsedLocale);
    log.info(
        "Locale for validation messages: %s"
            .formatted(
                this.fhirContextService.getFhirContext().getLocalizer().getLocale().toString()));
    log.info("Minimum severity of the outcome: %s".formatted(this.minSeverityOutcome));
    // Eager creation of validator for validation performance
    Map<Class<? extends MetadataResource>, Map<String, IBaseResource>> profiles =
        profileParserService.getParseProfiles();
    this.validator = createAndInitValidator(profiles);
    this.filteredMessagePrefixes = loadMessagesToFilter(parsedLocale);
    Locale.setDefault(oldLocale); // Put back global default locale to avoid side effects
  }

  /**
   * Does one validation with a code system, so DefaultProfileValidationSupport loads all resources.
   *
   * @param fhirValidator validator to initialize
   */
  private static void initValidator(FhirValidator fhirValidator) {
    Observation observation = new Observation();
    observation.setStatus(Observation.ObservationStatus.FINAL);
    observation
        .getCode()
        .addCoding()
        .setSystem("http://loinc.org")
        .setCode("789-8")
        .setDisplay("Erythrocytes [#/volume] in Blood by Automated count");
    Bundle bundle = new Bundle();
    bundle
        .addEntry()
        .setResource(observation)
        .getRequest()
        .setUrl("Observation")
        .setMethod(Bundle.HTTPVerb.POST);

    fhirValidator.validateWithResult(bundle);
  }

  private Set<String> loadMessagesToFilter(Locale parsedLocale) {
    final ResourceBundle resourceBundle = ResourceBundle.getBundle("Messages", parsedLocale);
    filteredMessagePrefixes = new HashSet<>(FILTERED_MESSAGES_KEYS.size());
    return FILTERED_MESSAGES_KEYS.stream()
        .map(resourceBundle::getString)
        .map(
            message -> {
              final int indexParameter = message.indexOf("{");
              return message.substring(0, indexParameter > 0 ? indexParameter : message.length());
            })
        .collect(Collectors.toSet());
  }

  private FhirValidator createAndInitValidator(
      Map<Class<? extends MetadataResource>, Map<String, IBaseResource>> profiles) {
    Map<String, IBaseResource> structureDefinitions = profiles.get(StructureDefinition.class);
    Map<String, IBaseResource> valueSets = profiles.get(ValueSet.class);
    Map<String, IBaseResource> codeSystems = profiles.get(CodeSystem.class);
    Map<String, IBaseResource> questionnaires = profiles.get(Questionnaire.class);

    log.info("Start creating and initializing fhir validator");
    FhirContext fhirContext = fhirContextService.getFhirContext();
    FhirValidator fhirValidator = fhirContext.newValidator();

    IValidationSupport prePopulatedValidationSupport =
        new DemisPrePopulatedValidationSupportHapi4(
            fhirContext, structureDefinitions, valueSets, codeSystems, questionnaires);

    DefaultProfileValidationSupport defaultProfileValidationSupport =
        new DefaultProfileValidationSupport(fhirContext);

    InMemoryTerminologyServerValidationSupport inMemoryTerminologyServerValidationSupport =
        new InMemoryTerminologyServerValidationSupport(fhirContext);

    SnapshotGeneratingValidationSupport snapshotGenerator =
        new SnapshotGeneratingValidationSupport(fhirContext);

    CommonCodeSystemsTerminologyService commonCodeSystemsTerminologyService =
        new CommonCodeSystemsTerminologyService(fhirContext);

    ValidationSupportChain validationSupportChain =
        new ValidationSupportChain(
            prePopulatedValidationSupport,
            defaultProfileValidationSupport,
            inMemoryTerminologyServerValidationSupport,
            commonCodeSystemsTerminologyService,
            snapshotGenerator);

    FhirInstanceValidator fhirModule = new FhirInstanceValidator(validationSupportChain);
    fhirModule.setErrorForUnknownProfiles(true);
    fhirValidator.registerValidatorModule(fhirModule);
    initValidator(fhirValidator);
    log.info("Finished creating and initializing fhir validator");

    return fhirValidator;
  }

  private OperationOutcome toOperationOutcome(ValidationResult validationResult) {
    List<SingleValidationMessage> collect =
        validationResult.getMessages().stream()
            .filter(this::isAllowedMessage)
            .filter(this::atLeastMinSeverity)
            .toList();
    ValidationResult filteredValidationResult =
        new ValidationResult(fhirContextService.getFhirContext(), collect);

    OperationOutcome outcome = new OperationOutcome();
    filteredValidationResult.populateOperationOutcome(outcome);

    return outcome;
  }

  private boolean isAllowedMessage(SingleValidationMessage message) {
    return filteredMessagePrefixes.stream().noneMatch(message.getMessage()::startsWith);
  }

  private boolean atLeastMinSeverity(SingleValidationMessage message) {
    return severityOrder(message.getSeverity()) >= minSeverityOutcome;
  }

  public OperationOutcome validate(String content) {
    ValidationResult validationResult = validator.validateWithResult(content);

    return toOperationOutcome(validationResult);
  }
}
