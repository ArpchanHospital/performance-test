package registries

import api.Constants.{LOGIN_LOCATION_UUID, LOGIN_USER, PROVIDER_UUID}
import api.DoctorHttpRequests._
import api.FrontdeskHttpRequests._
import api.HttpRequests._
import configurations.Feeders.{identifierSourceId, identifierType}
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.util.Random

object Frontdesk {

  val goToRegistrationSearchPage: ChainBuilder = exec(
    getVisitLocation(LOGIN_LOCATION_UUID)
      .resources(
        getUser(LOGIN_USER).check(
          jsonPath("$..results[0].uuid").find.saveAs("runTimeUuid")
        ),
        getSession,
        getProviderForUser("#{runTimeUuid}"),
        getGlobalProperty("mrs.genders"),
        getGlobalProperty("bahmni.relationshipTypeMap"),
        getAddressHierarchyLevel,
        getIdentifierTypes,
        getRelationshipTypes,
        getEntityMapping("loginlocation_visittype"),
        getPersonAttributeTypes,
        getRegistrationConcepts.check(jsonPath("$.visitTypes.OPD").find.saveAs("visit_type_id")),
        getByVisitLocation(LOGIN_LOCATION_UUID),
        getGlobalProperty("bahmni.enableAuditLog"),
        postAuditLog
      )
  )

  def performNameSearch(patientName: String): ChainBuilder = {
    exec(
      searchPatientUsingName(LOGIN_LOCATION_UUID, patientName)
        .check(jsonPath("$..uuid").findAll.transform(Random.shuffle(_).head).optional.saveAs("pt_uuID"))
        .resources(
          getPatientProfileAfterRegistration("#{pt_uuID}")
        )
    )
  }

  def performIdSearch(patientIdentifier: String): ChainBuilder = {
    exec(
      searchPatientUsingIdentifier(LOGIN_LOCATION_UUID, patientIdentifier)
        .check(jsonPath("$..uuid").findAll.transform(Random.shuffle(_).head).optional.saveAs("p_uuID"))
        .resources(
          getPatientProfileAfterRegistration("#{p_uuID}")
        )
    )
  }

  val startVisitForID: ChainBuilder = {
    exec(
      startVisitRequest("#{p_uuID}", "#{visit_type_id}", LOGIN_LOCATION_UUID)
    )
  }

  val startVisitForName: ChainBuilder = {
    exec(
      startVisitRequest("#{pt_uuID}", "#{visit_type_id}", LOGIN_LOCATION_UUID)
    )
  }

  val startVisitForCreatePatient: ChainBuilder = {
    exec(
      startVisitRequest("#{patient_uuid}", "#{visit_type_id}", LOGIN_LOCATION_UUID)

    )
  }

  val gotoCreatePatientPage: ChainBuilder = exec(
    getUser(LOGIN_USER)
      .check(
        jsonPath("$..results[0].uuid").find.saveAs("runTimeUuid")
      )
      .resources(
        getLoginLocations,
        getProviderForUser("#{runTimeUuid}"),
        postUserInfo("#{runTimeUuid}"),
        getSession,
        getVisitLocation(LOGIN_LOCATION_UUID),
        getRegistrationConcepts,
        getPersonaAttributeType,
        getIdentifierTypes.check(
          jsonPath("$[?(@.name==\"Patient Identifier\")].uuid").find.saveAs("identifier_type"),
          jsonPath("$[?(@.name==\"Patient Identifier\")].identifierSources..uuid").find.saveAs("identifier_sources_id")
        ),
        getAddressHierarchyLevel,
        getGlobalProperty("mrs.genders"),
        getRelationshipTypes,
        getGlobalProperty("bahmni.relationshipTypeMap"),
        getEntityMapping("loginlocation_visittype"),
        getGlobalProperty("bahmni.enableAuditLog"),
        postAuditLog,
        getGlobalProperty("concept.reasonForDeath")
      )
  ).exec { session =>
    identifierType = session("identifier_type").as[String]
    identifierSourceId = session("identifier_sources_id").as[String]
    session
  }

  var createPatient: ChainBuilder = {
    exec(
      createPatientRequest(ElFileBody("patient_profile.json"))
        .check(
          jsonPath("$.patient.uuid").saveAs("patient_uuid")
        )
        .resources(
          findEncounter("#{patient_uuid}"),
          activateVisit("#{patient_uuid}"),
          getNutrition,
          getObservation(Seq("Height", "Weight"), Map("patientUuid" -> "#{patient_uuid}")),
          getVital,
          getFeeInformation,
          getPatientProfileAfterRegistration("#{patient_uuid}")
        )
    )
  }

  def getActivePatients: ChainBuilder = {
    exec(
      getPatientsInSearchTab(LOGIN_LOCATION_UUID, PROVIDER_UUID, "emrapi.sqlSearch.activePatients")
        .check(
          jsonPath("$..uuid").findAll.saveAs("patientUUIDs"),
        )
        .resources(
          getUser(LOGIN_USER)
            .check(
              jsonPath("$..results[0].uuid").find.saveAs("runTimeUuid")
            ),
          getSession,
          getRegistrationConcepts,
          getGlobalProperty("mrs.genders"),
          getIdentifierTypes
        )
    )
      .exec(getProviderForUser("#{runTimeUuid}"))
  }

  def getPatientImages = {
    var size: Int = 0
    exec(session => {
      size = session("patientUUIDs").as[Vector[String]].size
      if (size > 5) {
        size = 5
      }
      val patientUUIDs = session("patientUUIDs").as[Vector[String]].slice(0, size)
      session.set("indexed", patientUUIDs)
    })
      .foreach("#{indexed}", "index") {
        exec(getPatientImage("#{index}"))
      }
  }

  def goToPatientDocumentUpload ={
    exec(getPatientFull("#{pt_uuID}")
      .resources(
        getVisitType,
        findEncounter("#{pt_uuID}","a0cef4a7-2796-11ed-89d6-02500e1a53fa","9cf74449-2796-11ed-89d6-02500e1a53fa"),
        getPatientDocumentConcept,
        getVisits("#{pt_uuID}"),
        getEncounterByEncounterTypeUuid("#{pt_uuID}","9cf74449-2796-11ed-89d6-02500e1a53fa")
      )
    )
  }


}
