package io.queries;

import ch.ethz.sis.openbis.generic.asapi.v3.IApplicationServerApi;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.common.search.SearchResult;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.sample.Sample;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.sample.fetchoptions.SampleFetchOptions;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.sample.search.SampleSearchCriteria;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.vocabulary.VocabularyTerm;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.vocabulary.fetchoptions.VocabularyTermFetchOptions;
import ch.ethz.sis.openbis.generic.asapi.v3.dto.vocabulary.search.VocabularyTermSearchCriteria;
import io.queries.utils.Helpers;
import io.queries.utils.OpenBisTerminology;
import io.queries.utils.SpaceBlackList;
import io.webservice.REST;
import model.data.ChartConfig;
import model.data.ChartSettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrganismCountQuery extends AQuery {

    private enum DOMAIN {Bacteria, Eukaryota, Viruses}
    private enum TYPES {Domain, _Genus, _Species}
    private final String ncbiTaxanomyRestUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=taxonomy&id=";

    private final Map<String, Integer> organismCountMap = new HashMap<>();
    private final Map<String, String> organismDomainMap = new HashMap<>();
    private final Map<String, String> organismGenusMap = new HashMap<>();

    //Result maps
    private final Map<String, Integer> domainCountMap = new HashMap<>();

    private final Map<String, Integer> eukaryotaGenusCountMap = new HashMap<>();
    private final Map<String, Integer> bacteriaGenusCountMap = new HashMap<>();
    private final Map<String, Integer> virusesGenusCountMap = new HashMap<>();

    private final Map<String, Integer> eukaryotaSpeciesCountMap = new HashMap<>();
    private final Map<String, Integer> bacteriaSpeciesCountMap = new HashMap<>();
    private final Map<String, Integer> virusesSpeciesCountMap = new HashMap<>();

    public OrganismCountQuery(IApplicationServerApi v3, String sessionToken) {
        super(v3, sessionToken);
    }

    @Override
    public Map<String, ChartConfig> query() {

        clearMaps();
        countSamplesPerOrganism(retrieveSamplesFromOpenBis());
        setOrganismToDomainAndGenusMap();

        generateDomainCountMap();
        generateGenusCountMap();
        generateSpeciesCountMap();

        Map<String, ChartConfig> result = new HashMap<>();

        //Add Domain to config
        result.put(TYPES.Domain.toString(), generateChartConfig(domainCountMap, TYPES.Domain.toString(), "Sample Count by Domain"));

        //add Eukaryota_Genus etc to config
        result.put(DOMAIN.Eukaryota.toString().concat(TYPES._Genus.toString()), generateChartConfig(eukaryotaGenusCountMap,  DOMAIN.Eukaryota.toString(), "Sample Count Eukaryota"));
        result.put(DOMAIN.Bacteria.toString().concat(TYPES._Genus.toString()), generateChartConfig(bacteriaGenusCountMap,  DOMAIN.Bacteria.toString(), "Sample Count Bacteria"));
        result.put(DOMAIN.Viruses.toString().concat(TYPES._Genus.toString()), generateChartConfig(virusesGenusCountMap,  DOMAIN.Viruses.toString(), "Sample Count Viruses"));

        //add Eukaryota_Species etc to config
        result.put(DOMAIN.Eukaryota.toString().concat(TYPES._Species.toString()), generateChartConfig(eukaryotaSpeciesCountMap,  DOMAIN.Eukaryota.toString(), ""));
        result.put(DOMAIN.Bacteria.toString().concat(TYPES._Species.toString()), generateChartConfig(bacteriaSpeciesCountMap, DOMAIN.Bacteria.toString(), ""));
        result.put(DOMAIN.Viruses.toString().concat(TYPES._Species.toString()), generateChartConfig(virusesSpeciesCountMap,  DOMAIN.Viruses.toString(), ""));

        return result;
    }

    private void clearMaps(){
        domainCountMap.clear();
        organismCountMap.clear();
        organismDomainMap.clear();
        organismGenusMap.clear();

        eukaryotaSpeciesCountMap.clear();
        eukaryotaGenusCountMap.clear();

        bacteriaGenusCountMap.clear();
        bacteriaSpeciesCountMap.clear();

        virusesGenusCountMap.clear();
        virusesSpeciesCountMap.clear();
    }

    private SearchResult<Sample> retrieveSamplesFromOpenBis() {

        SampleSearchCriteria sampleSourcesCriteria = new SampleSearchCriteria();
        sampleSourcesCriteria.withSpace();
        sampleSourcesCriteria.withType().withCode().thatEquals(OpenBisTerminology.BIO_ENTITY.get());

        SampleFetchOptions fetchOptions = new SampleFetchOptions();
        fetchOptions.withType();
        fetchOptions.withProject();
        fetchOptions.withSpace();
        fetchOptions.withProperties();

        SearchResult<Sample> sampleSources = super.getV3().searchSamples(super.getSessionToken(), sampleSourcesCriteria, fetchOptions);

        return sampleSources;
    }

    private void countSamplesPerOrganism(SearchResult<Sample> sampleSources){
        //Iterate over all search results
        for (Sample experiment : sampleSources.getObjects()) {
            //If sample does not belong to a blacklisted space, then increment its organism count
            if (!SpaceBlackList.getList().contains(experiment.getSpace().getCode())) {
                Helpers.addEntryToStringCountMap(organismCountMap, experiment.getProperties().get(OpenBisTerminology.NCBI_ORGANISM.get()), 1);
            }
        }
    }

    private void setOrganismToDomainAndGenusMap() {
        for (String organism : organismCountMap.keySet()) {

            if (organism.equals("0")) { //0 = 'Other' in domain and can't be queried to NCBI
                organismDomainMap.put(organism, "Other");
            } else {
                retrieveDomainAndGenusFromNCBI(organism);
            }
        }
    }

    private void retrieveDomainAndGenusFromNCBI(String organism) {
        try (BufferedReader rd = new BufferedReader(new InputStreamReader((REST.call(ncbiTaxanomyRestUrl.concat(organism).concat("&retmode=xml")))))) {
            String line;
            String previousLine = "";
            while ((line = rd.readLine()) != null) {
                //Retrieve Genus
                if(line.contains("<Rank>genus")){
                    if(previousLine.contains("<ScientificName>")){
                        organismGenusMap.put(organism, getScientificName(previousLine));
                    }
                }

                //Retrieve Superkingdom
                if(line.contains("<Rank>superkingdom")){
                    if(previousLine.contains("<ScientificName>")){
                        organismDomainMap.put(organism, getScientificName(previousLine));
                    }
                }

                //Handle unclassified samples
                if(line.contains("<Lineage>unclassified sequences</Lineage>")){
                    organismDomainMap.put(organism, "unclassified sequences");
                }
                previousLine = line.trim();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getScientificName(String line){
        //Extract Scientific name with regex
        Pattern p = Pattern.compile("(<ScientificName>)(.*?)(<\\/ScientificName>)");
        Matcher m = p.matcher(line);
        m.find();

        return m.group(2);
    }

    private void generateDomainCountMap(){
        for (String organism : organismDomainMap.keySet()) {
            Helpers.addEntryToStringCountMap(domainCountMap, organismDomainMap.get(organism), organismCountMap.get(organism));
        }
    }

    private void generateGenusCountMap() {

        for (String organism : organismGenusMap.keySet()) {
            if (organismDomainMap.get(organism).equals(DOMAIN.Eukaryota.toString())) {
                Helpers.addEntryToStringCountMap(eukaryotaGenusCountMap, organismGenusMap.get(organism), organismCountMap.get(organism));
            }
            if (organismDomainMap.get(organism).equals(DOMAIN.Bacteria.toString())) {
                Helpers.addEntryToStringCountMap(bacteriaGenusCountMap, organismGenusMap.get(organism), organismCountMap.get(organism));
            }
            if (organismDomainMap.get(organism).equals(DOMAIN.Viruses.toString())) {
                Helpers.addEntryToStringCountMap(virusesGenusCountMap, organismGenusMap.get(organism), organismCountMap.get(organism));
            }
        }
    }

    private void generateSpeciesCountMap() {

        Map<String, String> vocabularyMap = mapTaxonomyIDToName();

        for (String organism : organismDomainMap.keySet()) {

            if (organismDomainMap.get(organism).equals(DOMAIN.Eukaryota.toString())) {
                eukaryotaSpeciesCountMap.put(vocabularyMap.get(organism), organismCountMap.get(organism));
            }
            if (organismDomainMap.get(organism).equals(DOMAIN.Bacteria.toString())) {
                bacteriaSpeciesCountMap.put(vocabularyMap.get(organism), organismCountMap.get(organism));
            }
            if (organismDomainMap.get(organism).equals(DOMAIN.Viruses.toString())) {
                virusesSpeciesCountMap.put(vocabularyMap.get(organism), organismCountMap.get(organism));
            }
        }
    }

    private Map<String, String> mapTaxonomyIDToName(){
        VocabularyTermFetchOptions vocabularyFetchOptions = new VocabularyTermFetchOptions();
        vocabularyFetchOptions.withVocabulary();

        VocabularyTermSearchCriteria vocabularyTermSearchCriteria = new VocabularyTermSearchCriteria();
        vocabularyTermSearchCriteria.withCode();

        SearchResult<VocabularyTerm> vocabularyTermSearchResult = super.getV3().searchVocabularyTerms(super.getSessionToken(), vocabularyTermSearchCriteria, vocabularyFetchOptions);

        Map<java.lang.String, java.lang.String> vocabularyMap = new HashMap<>();

        for (VocabularyTerm v : vocabularyTermSearchResult.getObjects()) {
            vocabularyMap.put(v.getCode(), v.getLabel());
        }

        return vocabularyMap;
    }

    //Generate your chart config
    private ChartConfig generateChartConfig(Map<String, Integer> result, String name, String title) {

        ChartConfig organismCount = new ChartConfig();

        //Add chart settings
        ChartSettings organismCountSettings = new ChartSettings(title);
        //Set xCategories
        String[] organism = result.keySet().toArray(new String[result.keySet().size()]);
        Arrays.sort(organism);
        organismCountSettings.setxCategories(new ArrayList<>(Arrays.asList(organism)));

        //Add settings to chart config
        organismCount.setSettings(organismCountSettings);


        //Add chart data: be careful with order of data: must match xCategory order
        Map<Object, ArrayList<Object>> count = new HashMap<>();
        ArrayList<Object> list = new ArrayList<>();
        for (String s : organism) {
            list.add(result.get(s));
        }
        count.put(name, list);
        organismCount.setData(count);

        return organismCount;
    }
}
