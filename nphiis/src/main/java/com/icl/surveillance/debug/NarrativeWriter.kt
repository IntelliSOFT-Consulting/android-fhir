package com.icl.surveillance.debug

import kotlin.random.Random

/**
 * DEBUG ONLY. Writes realistic free-text answers for synthetic records, so generated data reads
 * like real field reports instead of copies of the seed text.
 *
 * Text is picked per field [Topic] and filled from a per-record [Record], so all narrative fields
 * of one record talk about the same disease, place and channel.
 */
internal object NarrativeWriter {

    enum class Topic {
        HARMFUL_PRACTICE, RUMOR, OBSERVATION, ACTIVITIES, CONCLUSION,
        ACTION_TAKEN, CHALLENGES, RECOMMENDATIONS, PARTNERS, GENERIC
    }

    class Record(
        val random: Random,
        val disease: String,
        val place: String,
        val channel: String,
        val group: String,
    )

    fun newRecord(random: Random, diseases: List<String>, places: List<String>) = Record(
        random = random,
        disease = diseases.random(random),
        place = places.random(random),
        channel = CHANNELS.random(random),
        group = GROUPS.random(random),
    )

    /** Picks the topic from the question text; null means "leave the field as it was". */
    fun topicOf(questionText: String): Topic? {
        val t = questionText.lowercase()
        return when {
            t.contains("harmful practice") -> Topic.HARMFUL_PRACTICE
            t.contains("negative comment") || t.contains("misinformation") ||
                    t.contains("rumour about") || t.contains("rumor about") -> Topic.RUMOR
            t.contains("what you did") || t.contains("steps") || t.contains("corrective") ||
                    t.contains("action") && !t.contains("conclusion") -> Topic.ACTION_TAKEN
            t.contains("conclusion") -> Topic.CONCLUSION
            t.contains("activities") -> Topic.ACTIVITIES
            t.contains("observation") || t.contains("comment") -> Topic.OBSERVATION
            t.contains("challenge") -> Topic.CHALLENGES
            t.contains("recommendation") -> Topic.RECOMMENDATIONS
            t.contains("partner") -> Topic.PARTNERS
            else -> null
        }
    }

    fun write(topic: Topic, r: Record): String {
        val sentences = when (topic) {
            Topic.HARMFUL_PRACTICE -> listOf(HARMFUL.pick(r), HARMFUL_DETAIL.pick(r))
            Topic.RUMOR -> listOf(RUMORS.pick(r), RUMOR_SPREAD.pick(r))
            Topic.OBSERVATION -> listOf(OBSERVATIONS.pick(r), OBSERVATIONS_2.pick(r))
            Topic.ACTIVITIES -> listOf(ACTIVITIES.pick(r), ACTIVITIES_2.pick(r))
            Topic.CONCLUSION -> listOf(CONCLUSIONS.pick(r), RECOMMENDATIONS.pick(r))
            Topic.ACTION_TAKEN -> listOf(ACTIONS.pick(r), ACTIONS_2.pick(r))
            Topic.CHALLENGES -> listOf(CHALLENGES.pick(r))
            Topic.RECOMMENDATIONS -> listOf(RECOMMENDATIONS.pick(r))
            Topic.PARTNERS -> listOf(PARTNERS.pick(r))
            Topic.GENERIC -> listOf(OBSERVATIONS.pick(r))
        }
        return sentences.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun List<String>.pick(r: Record): String = random(r.random)
        .replace("{disease}", r.disease)
        .replace("{place}", r.place)
        .replace("{channel}", r.channel)
        .replace("{group}", r.group)
        .replace("{days}", (2 + r.random.nextInt(12)).toString())
        .replace("{n}", (3 + r.random.nextInt(40)).toString())

    val DISEASES_GENERAL = listOf(
        "mpox", "measles", "cholera", "polio", "anthrax", "rabies",
        "malaria", "COVID-19", "dengue", "Rift Valley fever"
    )

    private val CHANNELS = listOf(
        "WhatsApp groups", "a Facebook post", "TikTok videos", "a local radio call-in show",
        "word of mouth at the market", "a church gathering", "a chief's baraza",
        "boda boda riders at the stage", "a school parents' meeting", "a vernacular radio station"
    )

    private val GROUPS = listOf(
        "parents of young children", "pregnant women", "boda boda riders", "traders at the market",
        "school-going youth", "pastoralist households", "fisherfolk", "church members",
        "elderly residents", "casual labourers"
    )

    private val HARMFUL = listOf(
        "Some households in {place} are treating suspected {disease} cases with herbal concoctions at home instead of going to the health facility.",
        "Caregivers in {place} are hiding children with rashes and fever from community health promoters.",
        "Residents of {place} are refusing to let their children be vaccinated during the ongoing campaign.",
        "Families in {place} are drinking untreated water from the river despite the {disease} alert.",
        "A traditional healer in {place} is offering cuts and smoke treatment for people with {disease}-like symptoms.",
        "Mourners in {place} continued washing and touching the body at a funeral of a suspected {disease} case.",
        "Some {group} in {place} are slaughtering and eating meat from animals that died suddenly.",
        "Patients in {place} are stopping treatment early once symptoms improve."
    )

    private val HARMFUL_DETAIL = listOf(
        "The practice has been going on for about {days} days.",
        "Community health promoters estimate about {n} households are involved.",
        "It is mostly reported among {group}.",
        "Local leaders are aware but have not intervened.",
        "The practice was reported to the area chief."
    )

    private val RUMORS = listOf(
        "People are saying the vaccines being given in {place} cause infertility in young women.",
        "There is a claim that {disease} is spread by the new mobile phone masts in {place}.",
        "Residents believe {disease} is a curse and cannot be treated at the hospital.",
        "A message is circulating that drinking salt water or herbal tea prevents {disease}.",
        "Some say the health workers going door to door in {place} are spreading {disease}.",
        "It is being said that only foreigners and truck drivers can get {disease}.",
        "There is a rumour that the government is hiding a large number of {disease} deaths in {place}.",
        "People claim that the vaccines given during the campaign contain a tracking chip.",
        "A post claims that hospitals are paid for every patient they report as {disease}."
    )

    private val RUMOR_SPREAD = listOf(
        "The message has been shared widely through {channel}.",
        "It was first heard through {channel} and spread to neighbouring villages.",
        "Many {group} have heard it and some are now avoiding the health facility.",
        "It has been circulating for about {days} days.",
        "Attendance at the immunisation clinic has dropped since it started."
    )

    private val OBSERVATIONS = listOf(
        "Clinic attendance in {place} has reduced over the last {days} days.",
        "Community members are anxious and asking for clear information on {disease}.",
        "The rumour is most common among {group}.",
        "Several people asked the CHP whether the information was true.",
        "No unusual illness or deaths were reported in {place} at the time of reporting.",
        "Some community members are already correcting the message among their neighbours."
    )

    private val OBSERVATIONS_2 = listOf(
        "Follow-up with the area chief is recommended.",
        "The CHP will continue monitoring the situation.",
        "Messaging in the local language would help.",
        "Religious leaders have offered to support awareness.",
        ""
    )

    private val ACTIVITIES = listOf(
        "Visited {place} and held discussions with the village elder and CHPs.",
        "Met {n} community members at a baraza in {place} to verify the report.",
        "Conducted house-to-house visits in {place} and interviewed affected households.",
        "Visited the nearest health facility and reviewed the outpatient register for {disease}-like cases.",
        "Held a meeting with religious leaders and the area chief in {place}."
    )

    private val ACTIVITIES_2 = listOf(
        "Reviewed the messages circulating on social media with the sub-county health promotion officer.",
        "Collected contacts of persons who first shared the message.",
        "Checked school absenteeism records for the last two weeks.",
        "Shared health education materials on {disease} with the households visited."
    )

    private val CONCLUSIONS = listOf(
        "The report was found to be a rumour; no {disease} cases were identified in {place}.",
        "The information was misinformation spread through {channel}.",
        "One suspected {disease} case was identified and referred for sampling.",
        "The harmful practice was confirmed among a small number of households.",
        "The concern was partly true and needs continued community engagement."
    )

    private val ACTIONS = listOf(
        "Shared the message with the CHA and the sub-county surveillance officer.",
        "Reported the rumour to the area chief and the health facility in-charge.",
        "Talked to the people sharing the message and gave them the correct information.",
        "Forwarded the message to the county health promotion team for verification.",
        "Organised a short health talk at the market in {place}."
    )

    private val ACTIONS_2 = listOf(
        "Correct information was shared through the same channels the rumour used.",
        "The community was advised to report any sick person to the health facility.",
        "Follow-up visits are planned within {days} days.",
        "Religious leaders agreed to pass the message during services.",
        ""
    )

    private val CHALLENGES = listOf(
        "Long distances between households in {place} slowed down the activity.",
        "Some caregivers declined because of rumours about vaccine safety.",
        "Shortage of IEC materials and transport for the teams.",
        "Heavy rains made some villages in {place} inaccessible.",
        "Delayed payment of volunteer allowances affected turnout."
    )

    private val RECOMMENDATIONS = listOf(
        "Intensify community sensitisation on {disease} through radio and barazas.",
        "Engage religious and community leaders to counter the misinformation.",
        "Strengthen active case search in {place} for the next two weeks.",
        "Provide more IEC materials in the local language.",
        "Continue monitoring social media and community channels for new rumours and respond quickly."
    )

    private val PARTNERS = listOf(
        "Kenya Red Cross, AMREF Health Africa",
        "UNICEF, WHO, Kenya Red Cross",
        "World Vision, County Department of Health",
        "AMREF Health Africa, local CBOs",
        "Kenya Red Cross, faith-based organisations"
    )
}
