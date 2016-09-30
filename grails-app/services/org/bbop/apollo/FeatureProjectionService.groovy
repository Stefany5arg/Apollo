package org.bbop.apollo

import grails.transaction.NotTransactional
import grails.transaction.Transactional
import org.bbop.apollo.gwt.shared.FeatureStringEnum
import org.bbop.apollo.projection.Location
import org.bbop.apollo.projection.MultiSequenceProjection
import org.bbop.apollo.projection.ProjectionSequence
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONObject

@Transactional
class FeatureProjectionService {

    def projectionService
    def assemblageService
    def transcriptService

    // TODO: make this configurable somehow
    private final Integer DEFAULT_FOLDING_BUFFER = 20

    JSONArray projectTrack(JSONArray inputFeaturesArray, Assemblage assemblage, Boolean reverseProjection = false) {
        MultiSequenceProjection projection = projectionService.createMultiSequenceProjection(assemblage)
        return projectFeaturesArray(inputFeaturesArray, projection, reverseProjection, 0)
    }

    /**
     * Anything in this space is assumed to be visible
     * @param sequence
     * @param referenceTrackName
     * @param inputFeaturesArray
     * @return
     */
    @NotTransactional
    private JSONObject projectFeature(JSONObject inputFeature, MultiSequenceProjection projection, Boolean reverseProjection, Integer offset) {
        if (!inputFeature.has(FeatureStringEnum.LOCATION.value)) {
            return inputFeature
        }


        JSONObject locationObject = inputFeature.getJSONObject(FeatureStringEnum.LOCATION.value)

        Integer fmin = locationObject.has(FeatureStringEnum.FMIN.value) ? locationObject.getInt(FeatureStringEnum.FMIN.value) : null
        Integer fmax = locationObject.has(FeatureStringEnum.FMAX.value) ? locationObject.getInt(FeatureStringEnum.FMAX.value) : null
        ProjectionSequence projectionSequence1 = reverseProjection ? projection.getReverseProjectionSequence(fmin) : projection.getProjectionSequence(fmin + offset)
        ProjectionSequence projectionSequence2 = reverseProjection ? projection.getReverseProjectionSequence(fmax) : projection.getProjectionSequence(fmax + offset)

        if (reverseProjection) {
            // TODO: add reverse offset?
            fmin = fmin ? projection.projectReverseValue(fmin) : null

            // we are projecting a REVERSE, exclusive value
            fmax = fmax ? projection.projectReverseValue(fmax) : null
        } else {
            fmin = fmin ? projection.projectValue(fmin + offset) : null

            // we are projecting an exclusive value
            fmax = fmax ? projection.projectValue(fmax + offset) : null
        }

        if (fmin != null) {
            locationObject.put(FeatureStringEnum.FMIN.value, fmin)
        }
        if (fmax) {
            locationObject.put(FeatureStringEnum.FMAX.value, fmax)
        }
        // if we don't have a sequence .. need to assign one
        if (projectionSequence1 && projectionSequence2) {
            // case 1, projectionSequence1 exists and is equals to projectionSequence2
            if (projectionSequence1.name == projectionSequence2.name) {
                if (!locationObject.sequence) {
                    locationObject.put(FeatureStringEnum.SEQUENCE.value, projectionSequence1.name)
                }
                projectionService.reverseLocation(projectionSequence1, locationObject)
            } else if (projectionSequence1.name != projectionSequence2.name) {
                locationObject.put(FeatureStringEnum.SEQUENCE.value, "[{\"name\":\"" + projectionSequence1.name + "\"},{\"name\":\"" + projectionSequence2.name + "\"}]")
                // TODO: not sure how to handle this case
                assert projectionSequence1.reverse == projectionSequence2.reverse
            }
        } else if (projectionSequence1) {
            if (!locationObject.sequence) {
                locationObject.put(FeatureStringEnum.SEQUENCE.value, projectionSequence1.name)
            }
            projectionService.reverseLocation(projectionSequence1, locationObject)
        } else if (projectionSequence2) {
            if (!locationObject.sequence) {
                locationObject.put(FeatureStringEnum.SEQUENCE.value, projectionSequence2.name)
            }
            projectionService.reverseLocation(projectionSequence2, locationObject)
        } else {
            log.debug("Neither projection is valid, so ignoring")
//            throw new AnnotationException("Neither projection sequence seems to be valid")
        }

        return inputFeature
    }

    private JSONArray projectFeaturesArray(JSONArray inputFeaturesArray, MultiSequenceProjection projection, Boolean reverseProjection, Integer offset) {
        for (int i = 0; i < inputFeaturesArray.size(); i++) {
            JSONObject inputFeature = inputFeaturesArray.getJSONObject(i)

            if (inputFeature.containsKey(FeatureStringEnum.SEQUENCE.value)) {
                String sequenceName = inputFeature.getString(FeatureStringEnum.SEQUENCE.value)
                offset = projection.getOffsetForSequence(sequenceName)

            } else {
                // no offset to calculate??
            }

            projectFeature(inputFeature, projection, reverseProjection, offset)

            if (inputFeature.has(FeatureStringEnum.CHILDREN.value)) {
                JSONArray childFeatures = inputFeature.getJSONArray(FeatureStringEnum.CHILDREN.value)
                projectFeaturesArray(childFeatures, projection, reverseProjection, offset)
            }
        }
        return inputFeaturesArray
    }

/**
 * This method calculates a new set of feature locations based on projection, removes the old one and adds the new one.
 *
 * Spefically this method allows us to calculate MULTIPLE project sequences.
 *
 * @param multiSequenceProjection Projection context
 * @param feature Feature to set feature locations on
 * @param min fmin provided as a PROJECTED coordinate
 * @param max fmax provided as a PROJECTED coordinate
 * @return
 */
    def setFeatureLocationsForProjection(MultiSequenceProjection multiSequenceProjection, Feature feature, Integer min, Integer max) {
        // TODO: optimize for feature locations belonging to the same sequence (the most common case)
        def featureLocationList = FeatureLocation.findAllByFeature(feature)
        Integer oldStrand = featureLocationList.first().strand
        feature.featureLocations.clear()

        // this will only return valid projection sequences
        List<ProjectionSequence> projectionSequenceList = multiSequenceProjection.getReverseProjectionSequences(min, max)

        // they should be ordered, right?
        int rank = 0
        int firstIndex = 0
        int lastIndex = projectionSequenceList.size() - 1
//        for(projectionSequence in projectionSequenceList){
        projectionSequenceList.eachWithIndex { ProjectionSequence projectionSequence, int i ->

            int calculatedMin
            int calculatedMax
            boolean calculatedMinPartial = true
            boolean calculatedMaxPartial = true

            Organism organism = Organism.findByCommonName(projectionSequence.organism)
            Sequence sequence = Sequence.findByNameAndOrganism(projectionSequence.name, organism)

            if (projectionSequence.reverse) {
                // if first index, then we calculate the min
                if (i == firstIndex) {
                    calculatedMin = projectionSequence.end - min - projectionSequence.offset
                    calculatedMinPartial = false
                }
                // if the min is in the middle, then it must be 0
                // if the min is the last, then it must be 0
                else {
                    calculatedMin = projectionSequence.end
                }

                // if the max if the last, then we calculate it properly
                if (i == lastIndex) {
                    calculatedMax = projectionSequence.end - max - projectionSequence.offset
                    calculatedMaxPartial = false
                } else {
                    // if the max is in the middle, then it must be the sequence.unprojectedLength
                    // if the max is in the first of many, then it must be sequence.unprojectedLength
//                    calculatedMax = projectionSequence.unprojectedLength
                    calculatedMax = 0
                }

                // swap values
                int temp = calculatedMin
                calculatedMin = calculatedMax
                calculatedMax = temp

            } else {
                // if first index, then we calculate the min
                if (i == firstIndex) {
                    calculatedMin = min + projectionSequence.start - projectionSequence.offset
                    calculatedMinPartial = false
                }
                // if the min is in the middle, then it must be 0
                // if the min is the last, then it must be 0
                else {
                    calculatedMin = 0
                }

                // if the max if the last, then we calculate it properly
                if (i == lastIndex) {
                    calculatedMax = max + projectionSequence.start - projectionSequence.offset
                    calculatedMaxPartial = false
                } else {
                    // if the max is in the middle, then it must be the sequence.unprojectedLength
                    // if the max is in the first of many, then it must be sequence.unprojectedLength
                    calculatedMax = projectionSequence.unprojectedLength
                }
            }

            FeatureLocation featureLocation = new FeatureLocation(
                    fmin: calculatedMin,
                    fmax: calculatedMax,
                    isFmaxPartial: calculatedMaxPartial,
                    isFminPartial: calculatedMinPartial,
                    sequence: sequence,
                    feature: feature,
                    rank: rank,
                    strand: oldStrand
            ).save(insert: true, failOnError: true, flush: true)
            feature.addToFeatureLocations(featureLocation)
            ++rank
        }
        feature.save(flush: true, insert: false)
        return feature
    }

    /**
     * This method generates projections for a feature.
     *
     * For each exon in the trancript, add a location to the projection
     *
     * @param feature
     */
    def addExonLocationsToProjection(Feature feature, MultiSequenceProjection projection) {

        if(feature instanceof Transcript){
            Transcript transcript = (Transcript) feature
            for(Exon exon in transcriptService.getExons(transcript)){
                // TODO: this does not work if we cross th sequence boundary, but good enough for now
                ProjectionSequence projectionSequence = projection.getReverseProjectionSequence(exon.fmin)
                Location location = new Location(
                        min: exon.fmin-DEFAULT_FOLDING_BUFFER,
                        max: exon.fmax+DEFAULT_FOLDING_BUFFER,
                        sequence: projectionSequence
                )
                // if it already has this then it won't matter
                projection.addLocation(location)
            }
        }
        else{
            ProjectionSequence projectionSequence = projection.getReverseProjectionSequence(feature.fmin)
            Location location = new Location(
                    min: feature.fmin-DEFAULT_FOLDING_BUFFER,
                    max: feature.fmax+DEFAULT_FOLDING_BUFFER,
                    sequence: projectionSequence
            )
            // if it already has this then it won't matter
            projection.addLocation(location)
        }

        return projection
    }

    /**
     * The goal here is to expand the JSONObject passed in by collapsing all of the subfeatures of any features labeled but not actually expanded.
     *
     *
     *
     * 1 - Create a "Discontinuous Projection" for any collapsed features in the JSONObject
     * @param jsonObject
     * @return
     */
    JSONObject expandProjectionJson(JSONObject jsonObject) {

        JSONArray sequenceList = jsonObject.getJSONArray(FeatureStringEnum.SEQUENCE_LIST.value)
        MultiSequenceProjection multiSequenceProjection = projectionService.getProjection(jsonObject)

        for (JSONObject sequenceObject in sequenceList) {
            JSONObject featureObject = sequenceObject.feature
            if (featureObject) {
                // if collapsed, but NO PROJECTION at the sequenceobject level then add one
                Feature f = Feature.findByName(featureObject.name)
                // TODO: should use scaffold and organism as well in a criteria query
                if (featureObject.collapse) {
                    multiSequenceProjection = addExonLocationsToProjection(f, multiSequenceProjection)
                }
                // remove the locations for that region.  Adding a single overlap will do this automatically.
                else {
                    // TODO: we need a proper method for doing this.
                    multiSequenceProjection.clear()
//                    Location location = new Location(
//                            min: f.fmin,
//                            max: f.fmax,
//                            sequence: multiSequenceProjection.getReverseProjectionSequence(f.fmin)
//                    )
//                    multiSequenceProjection.addLocation(location)

                }
            }
        }

        sequenceList = projectionService.generateSequenceListFromProjection(multiSequenceProjection)
        jsonObject.put(FeatureStringEnum.SEQUENCE_LIST.value,sequenceList)


        return jsonObject
    }
}
