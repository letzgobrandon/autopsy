/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.sleuthkit.autopsy.discovery;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.openide.util.NbBundle;
import org.sleuthkit.datamodel.BlackboardArtifact;

/**
 * Utility enums for searches made for artifacts with Discovery.
 */
public class ArtifactSearchData extends SearchData {

    private static final Set<BlackboardArtifact.ARTIFACT_TYPE> DOMAIN_ARTIFACT_TYPES = EnumSet.of(BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_BOOKMARK, BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_CACHE, BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_COOKIE, BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_DOWNLOAD, BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_HISTORY, BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_SEARCH_QUERY, BlackboardArtifact.ARTIFACT_TYPE.TSK_EMAIL_MSG);

    @Override
    ResultType getResultType() {
        return ResultType.ARTIFACT;
    }

    /**
     * Enum representing the file type. We don't simply use
     * FileTypeUtils.FileTypeCategory because: - Some file types categories
     * overlap - It is convenient to have the "OTHER" option for files that
     * don't match the given types
     */
    @NbBundle.Messages({
        "ArtifactSearchData.ArtifactType.Domain.displayName=Domain",
        "ArtifactSearchData.ArtifactType.Other.displayName=Other"})
    enum ArtifactType {

        DOMAIN(0, Bundle.ArtifactSearchData_ArtifactType_Domain_displayName(), DOMAIN_ARTIFACT_TYPES),
        OTHER(1, Bundle.ArtifactSearchData_ArtifactType_Other_displayName(), new HashSet<>());

        private final int ranking;  // For ordering in the UI
        private final String displayName;
        private final Set<BlackboardArtifact.ARTIFACT_TYPE> artifactTypes = new HashSet<>();

        ArtifactType(int value, String displayName, Set<BlackboardArtifact.ARTIFACT_TYPE> types) {
            this.ranking = value;
            this.displayName = displayName;
            this.artifactTypes.addAll(types);
        }

        /**
         * Get the BlackboardArtifact types matching this category.
         *
         * @return Collection of BlackboardArtifact types.
         */
        Collection<BlackboardArtifact.ARTIFACT_TYPE> getBlackboardTypes() {
            return Collections.unmodifiableCollection(artifactTypes);
        }

        @Override
        public String toString() {
            return displayName;
        }

        /**
         * Get the rank for sorting.
         *
         * @return the rank (lower should be displayed first)
         */
        int getRanking() {
            return ranking;
        }

        static ArtifactType fromBlackboardArtifact(final BlackboardArtifact.ARTIFACT_TYPE type) {
            switch (type) {
                case TSK_WEB_BOOKMARK:
                    return DOMAIN;
                default:
                    return OTHER;
            }
        }

    }

}
