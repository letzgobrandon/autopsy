/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-2019 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.datamodel;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import javax.swing.SwingWorker;
import org.openide.nodes.ChildFactory;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.openide.nodes.Sheet;
import org.openide.util.NbBundle;
import org.openide.util.NbBundle.Messages;
import org.openide.util.lookup.Lookups;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.core.UserPreferences;
import org.sleuthkit.autopsy.corecomponents.DataResultTopComponent;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.IngestManager;
import org.sleuthkit.autopsy.mainui.datamodel.FileTypeMimeSearchParams;
import org.sleuthkit.autopsy.mainui.datamodel.FileTypeSizeSearchParams;
import org.sleuthkit.autopsy.mainui.datamodel.FileTypeSizeSearchParams.FileSizeFilter;
import org.sleuthkit.autopsy.mainui.nodes.SelectionResponder;
import org.sleuthkit.autopsy.mainui.nodes.ViewsTypeFactory.FileSizeTypeFactory;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;

/**
 * Files by Size View node and related child nodes
 */
public class FileSize implements AutopsyVisitableItem {

    private static final Logger logger = Logger.getLogger(FileTypes.class.getName());

    private SleuthkitCase skCase;
    private final long filteringDSObjId; // 0 if not filtering/grouping by data source

    public FileSize(SleuthkitCase skCase) {
        this(skCase, 0);
    }

    public FileSize(SleuthkitCase skCase, long dsObjId) {
        this.skCase = skCase;
        this.filteringDSObjId = dsObjId;
    }

    @Override
    public <T> T accept(AutopsyItemVisitor<T> visitor) {
        return visitor.visit(this);
    }

    public SleuthkitCase getSleuthkitCase() {
        return this.skCase;
    }

    long filteringDataSourceObjId() {
        return this.filteringDSObjId;
    }

    /*
     * Root node. Children are nodes for specific sizes.
     */
    public static class FileSizeRootNode extends DisplayableItemNode {

        private static final String NAME = NbBundle.getMessage(FileSize.class, "FileSize.fileSizeRootNode.name");
        private final long dataSourceObjId;

        FileSizeRootNode(long datasourceObjId) {
            super(Children.create(new FileSizeTypeFactory(datasourceObjId > 0 ? datasourceObjId : null), true), Lookups.singleton(NAME));
            super.setName(NAME);
            super.setDisplayName(NAME);
            this.setIconBaseWithExtension("org/sleuthkit/autopsy/images/file-size-16.png"); //NON-NLS
            this.dataSourceObjId = datasourceObjId;
        }

        public Node clone() {
            return new FileSizeRootNode(this.dataSourceObjId);
        }
        
        @Override
        public boolean isLeafTypeNode() {
            return false;
        }

        @Override
        public <T> T accept(DisplayableItemNodeVisitor<T> visitor) {
            return visitor.visit(this);
        }

        @Override
        protected Sheet createSheet() {
            Sheet sheet = super.createSheet();
            Sheet.Set sheetSet = sheet.get(Sheet.PROPERTIES);
            if (sheetSet == null) {
                sheetSet = Sheet.createPropertiesSet();
                sheet.put(sheetSet);
            }

            sheetSet.put(new NodeProperty<>(NbBundle.getMessage(this.getClass(), "FileSize.createSheet.name.name"),
                    NbBundle.getMessage(this.getClass(), "FileSize.createSheet.name.displayName"),
                    NbBundle.getMessage(this.getClass(), "FileSize.createSheet.name.desc"),
                    NAME));
            return sheet;
        }

        @Override
        public String getItemType() {
            return getClass().getName();
        }
    }

    /*
     * Makes the children for specific sizes
     */
    public static class FileSizeRootChildren extends ChildFactory<FileSizeFilter> {

        private SleuthkitCase skCase;
        private final long datasourceObjId;
        private Observable notifier;

        public FileSizeRootChildren(SleuthkitCase skCase, long datasourceObjId) {
            this.skCase = skCase;
            this.datasourceObjId = datasourceObjId;
            notifier = new FileSizeRootChildrenObservable();
        }

        /**
         * Listens for case and ingest invest. Updates observers when events are
         * fired. Size-based nodes are listening to this for changes.
         */
        private static final class FileSizeRootChildrenObservable extends Observable {

            private static final Set<Case.Events> CASE_EVENTS_OF_INTEREST = EnumSet.of(Case.Events.DATA_SOURCE_ADDED, Case.Events.CURRENT_CASE);
            private static final Set<IngestManager.IngestJobEvent> INGEST_JOB_EVENTS_OF_INTEREST = EnumSet.of(IngestManager.IngestJobEvent.COMPLETED, IngestManager.IngestJobEvent.CANCELLED);
            private static final Set<IngestManager.IngestModuleEvent> INGEST_MODULE_EVENTS_OF_INTEREST = EnumSet.of(IngestManager.IngestModuleEvent.CONTENT_CHANGED);

            FileSizeRootChildrenObservable() {
                IngestManager.getInstance().addIngestJobEventListener(INGEST_JOB_EVENTS_OF_INTEREST, pcl);
                IngestManager.getInstance().addIngestModuleEventListener(INGEST_MODULE_EVENTS_OF_INTEREST, pcl);
                Case.addEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, pcl);
            }

            private void removeListeners() {
                deleteObservers();
                IngestManager.getInstance().removeIngestJobEventListener(pcl);
                IngestManager.getInstance().removeIngestModuleEventListener(pcl);
                Case.removeEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, pcl);
            }

            private final PropertyChangeListener pcl = (PropertyChangeEvent evt) -> {
                String eventType = evt.getPropertyName();

                if (eventType.equals(IngestManager.IngestModuleEvent.CONTENT_CHANGED.toString())) {
                    /**
                     * Checking for a current case is a stop gap measure until a
                     * different way of handling the closing of cases is worked
                     * out. Currently, remote events may be received for a case
                     * that is already closed.
                     */
                    try {
                        // new file was added
                        // @@@ could check the size here and only fire off updates if we know the file meets the min size criteria
                        Case.getCurrentCaseThrows();
                        update();
                    } catch (NoCurrentCaseException notUsed) {
                        /**
                         * Case is closed, do nothing.
                         */
                    }
                } else if (eventType.equals(IngestManager.IngestJobEvent.COMPLETED.toString())
                        || eventType.equals(IngestManager.IngestJobEvent.CANCELLED.toString())
                        || eventType.equals(Case.Events.DATA_SOURCE_ADDED.toString())) {
                    /**
                     * Checking for a current case is a stop gap measure until a
                     * different way of handling the closing of cases is worked
                     * out. Currently, remote events may be received for a case
                     * that is already closed.
                     */
                    try {
                        Case.getCurrentCaseThrows();
                        update();
                    } catch (NoCurrentCaseException notUsed) {
                        /**
                         * Case is closed, do nothing.
                         */
                    }
                } else if (eventType.equals(Case.Events.CURRENT_CASE.toString())) {
                    // case was closed. Remove listeners so that we don't get called with a stale case handle
                    if (evt.getNewValue() == null) {
                        removeListeners();
                    }
                }
            };

            private void update() {
                setChanged();
                notifyObservers();
            }
        }

        @Override
        protected boolean createKeys(List<FileSizeFilter> list) {
            list.addAll(Arrays.asList(FileSizeFilter.values()));
            return true;
        }

        @Override
        protected Node createNodeForKey(FileSizeFilter key) {
            return new FileSizeNode(skCase, key, notifier, datasourceObjId);
        }

        /*
         * Node for a specific size range. Children are files.
         */
        public class FileSizeNode extends DisplayableItemNode implements SelectionResponder{

            private final FileSizeFilter filter;
            private final long datasourceObjId;
            private long childCount = -1;
            private final SleuthkitCase skCase;

            // use version with observer instead so that it updates
            @Deprecated
            FileSizeNode(SleuthkitCase skCase, FileSizeFilter filter, long datasourceObjId) {
                super(Children.LEAF,
                        Lookups.fixed(filter.getDisplayName(),
                                new FileTypeSizeSearchParams(
                                        filter,
                                        datasourceObjId > 0 ? datasourceObjId : null)));
                this.filter = filter;
                this.datasourceObjId = datasourceObjId;
                this.skCase = skCase;
                init();
            }

            /**
             *
             * @param skCase
             * @param filter
             * @param o               Observable that provides updates when
             *                        events are fired
             * @param datasourceObjId filter by data source, if configured in
             *                        user preferences
             */
            FileSizeNode(SleuthkitCase skCase, FileSizeFilter filter, Observable o, long datasourceObjId) {
                super(Children.LEAF,
                        Lookups.fixed(filter.getDisplayName()));
                this.filter = filter;
                this.datasourceObjId = datasourceObjId;
                this.skCase = skCase;
                init();
                o.addObserver(new FileSizeNodeObserver());
            }
            
            @Override
            public void respondSelection(DataResultTopComponent dataResultPanel) {
                dataResultPanel.displayFileSizes(new FileTypeSizeSearchParams(
                                        filter,
                                        datasourceObjId > 0 ? datasourceObjId : null));
            }

            private void init() {
                super.setName(filter.getName());

                String tooltip = filter.getDisplayName();
                this.setShortDescription(tooltip);
                this.setIconBaseWithExtension("org/sleuthkit/autopsy/images/file-size-16.png"); //NON-NLS

                updateDisplayName();
            }

            @Override
            public String getItemType() {
                /**
                 * Return getClass().getName() + filter.getName() if custom
                 * settings are desired for different filters.
                 */
                return DisplayableItemNode.FILE_PARENT_NODE_KEY;
            }

            // update the display name when new events are fired
            private class FileSizeNodeObserver implements Observer {

                @Override
                public void update(Observable o, Object arg) {
                    updateDisplayName();
                }
            }

            @Override
            public <T> T accept(DisplayableItemNodeVisitor<T> visitor) {
                return visitor.visit(this);
            }

            @Override
            protected Sheet createSheet() {
                Sheet sheet = super.createSheet();
                Sheet.Set sheetSet = sheet.get(Sheet.PROPERTIES);
                if (sheetSet == null) {
                    sheetSet = Sheet.createPropertiesSet();
                    sheet.put(sheetSet);
                }

                sheetSet.put(new NodeProperty<>(NbBundle.getMessage(this.getClass(), "FileSize.createSheet.filterType.name"),
                        NbBundle.getMessage(this.getClass(), "FileSize.createSheet.filterType.displayName"),
                        NbBundle.getMessage(this.getClass(), "FileSize.createSheet.filterType.desc"),
                        filter.getDisplayName()));

                return sheet;
            }

            @Override
            public boolean isLeafTypeNode() {
                return true;
            }

            private long calculateChildCount() {
                try {
                    return skCase.countFilesWhere(makeQuery(filter, datasourceObjId));
                } catch (TskCoreException ex) {
                    logger.log(Level.SEVERE, "Error getting files by size search view count", ex); //NON-NLS
                    return 0;
                }
            }

            @Messages("FileSizeNode_counting_placeholder= (counting...)")
            private void updateDisplayName() {
                //only show "(counting...)" the first time, otherwise it is distracting.
                setDisplayName(filter.getDisplayName() + ((childCount < 0) ? Bundle.FileTypes_bgCounting_placeholder()
                        : (" (" + childCount + ")"))); //NON-NLS
                new SwingWorker<Long, Void>() {
                    @Override
                    protected Long doInBackground() throws Exception {
                        return calculateChildCount();
                    }

                    @Override
                    protected void done() {
                        try {
                            childCount = get();
                            setDisplayName(filter.getDisplayName() + " (" + childCount + ")"); //NON-NLS
                        } catch (InterruptedException | ExecutionException ex) {
                            setDisplayName(filter.getDisplayName());
                            logger.log(Level.WARNING, "Failed to get count of files for " + filter.getDisplayName(), ex); //NON-NLS
                        }
                    }
                }.execute();

            }

            private String makeQuery(FileSizeFilter filter, long filteringDSObjId) {
                String query;
                switch (filter) {
                    case SIZE_50_200:
                        query = "(size >= 50000000 AND size < 200000000)"; //NON-NLS
                        break;
                    case SIZE_200_1000:
                        query = "(size >= 200000000 AND size < 1000000000)"; //NON-NLS
                        break;

                    case SIZE_1000_:
                        query = "(size >= 1000000000)"; //NON-NLS
                        break;

                    default:
                        throw new IllegalArgumentException("Unsupported filter type to get files by size: " + filter); //NON-NLS
                }

                // Ignore unallocated block files.
                query = query + " AND (type != " + TskData.TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS.getFileType() + ")"; //NON-NLS

                // hide known files if specified by configuration
                query += (UserPreferences.hideKnownFilesInViewsTree() ? (" AND (known IS NULL OR known != " + TskData.FileKnown.KNOWN.getFileKnownValue() + ")") : ""); //NON-NLS

                // filter by datasource if indicated in case preferences
                if (filteringDSObjId > 0) {
                    query += " AND data_source_obj_id = " + filteringDSObjId;
                }

                return query;
            }
        }
    }
}
