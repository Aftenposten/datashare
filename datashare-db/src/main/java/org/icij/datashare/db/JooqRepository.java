package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.DocumentUserRecommendation;
import org.icij.datashare.Note;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.db.tables.records.DocumentRecord;
import org.icij.datashare.db.tables.records.DocumentTagRecord;
import org.icij.datashare.db.tables.records.DocumentUserRecommendationRecord;
import org.icij.datashare.db.tables.records.DocumentUserStarRecord;
import org.icij.datashare.db.tables.records.NamedEntityRecord;
import org.icij.datashare.db.tables.records.NoteRecord;
import org.icij.datashare.db.tables.records.ProjectRecord;
import org.icij.datashare.db.tables.records.UserHistoryProjectRecord;
import org.icij.datashare.db.tables.records.UserHistoryRecord;
import org.icij.datashare.db.tables.records.UserInventoryRecord;
import org.icij.datashare.json.JsonUtils;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.nio.charset.Charset.forName;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.Entity.LOGGER;
import static org.icij.datashare.UserEvent.Type.fromId;
import static org.icij.datashare.db.Tables.USER_HISTORY_PROJECT;
import static org.icij.datashare.db.tables.Document.DOCUMENT;
import static org.icij.datashare.db.tables.DocumentTag.DOCUMENT_TAG;
import static org.icij.datashare.db.tables.DocumentUserRecommendation.DOCUMENT_USER_RECOMMENDATION;
import static org.icij.datashare.db.tables.DocumentUserStar.DOCUMENT_USER_STAR;
import static org.icij.datashare.db.tables.NamedEntity.NAMED_ENTITY;
import static org.icij.datashare.db.tables.Note.NOTE;
import static org.icij.datashare.db.tables.Project.PROJECT;
import static org.icij.datashare.db.tables.UserHistory.USER_HISTORY;
import static org.icij.datashare.db.tables.UserInventory.USER_INVENTORY;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.text.Document.Status.fromCode;
import static org.icij.datashare.text.Language.parse;
import static org.icij.datashare.text.Project.project;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.countDistinct;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.using;
import static org.jooq.impl.DSL.value;

public class JooqRepository implements Repository {
    private final DataSource connectionProvider;
    private SQLDialect dialect;

    JooqRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public NamedEntity getNamedEntity(String id) {
        return createFrom(DSL.using(connectionProvider, dialect).
                selectFrom(NAMED_ENTITY).where(NAMED_ENTITY.ID.eq(id)).fetchOne());
    }

    @Override
    public void create(List<NamedEntity> neList) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        InsertValuesStep9<NamedEntityRecord, String, String, String, Short, String, String, String, String, Boolean>
                insertQuery = create.insertInto(NAMED_ENTITY,
                NAMED_ENTITY.ID, NAMED_ENTITY.MENTION, NAMED_ENTITY.OFFSETS, NAMED_ENTITY.EXTRACTOR,
                NAMED_ENTITY.CATEGORY, NAMED_ENTITY.DOC_ID, NAMED_ENTITY.ROOT_ID,
                NAMED_ENTITY.EXTRACTOR_LANGUAGE, NAMED_ENTITY.HIDDEN);
        neList.forEach(ne -> {
            try {
                insertQuery.values(
                        ne.getId(), ne.getMention(), MAPPER.writeValueAsString(ne.getOffsets()), ne.getExtractor().code,
                        ne.getCategory().getAbbreviation(), ne.getDocumentId(), ne.getRootDocument(),
                        ne.getExtractorLanguage().iso6391Code(), ne.isHidden());
            } catch (JsonProcessingException e) {
                LOGGER.error("cannot serialize offsets {}", ne.getOffsets());
            }
        });
        insertQuery.execute();
    }

    @Override
    public Document getDocument(final String id) {
        return createDocumentFrom(DSL.using(connectionProvider, dialect).
                selectFrom(DOCUMENT).where(DOCUMENT.ID.eq(id)).fetchOne());
    }

    @Override
    public void create(Document doc) {
        DSLContext ctx = DSL.using(connectionProvider, dialect);
        try {
            ctx.insertInto(DOCUMENT, DOCUMENT.PROJECT_ID,
                    DOCUMENT.ID, DOCUMENT.PATH, DOCUMENT.CONTENT, DOCUMENT.STATUS,
                    DOCUMENT.CHARSET, DOCUMENT.LANGUAGE, DOCUMENT.CONTENT_TYPE,
                    DOCUMENT.EXTRACTION_DATE, DOCUMENT.PARENT_ID, DOCUMENT.ROOT_ID,
                    DOCUMENT.EXTRACTION_LEVEL, DOCUMENT.CONTENT_LENGTH, DOCUMENT.METADATA, DOCUMENT.NER_MASK).
                    values(doc.getProject().getId(), doc.getId(), doc.getPath().toString(), doc.getContent(), doc.getStatus().code,
                            doc.getContentEncoding().toString(), doc.getLanguage().iso6391Code(), doc.getContentType(),
                            new Timestamp(doc.getExtractionDate().getTime()), doc.getParentDocument(), doc.getRootDocument(),
                            doc.getExtractionLevel(), doc.getContentLength(),
                            MAPPER.writeValueAsString(doc.getMetadata()), doc.getNerMask()).execute();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.selectFrom(DOCUMENT).where(
                condition("(ner_mask & ?) = 0", type.mask)).
                fetch().stream().map(this::createDocumentFrom).collect(toList());
    }

    @Override
    public List<Document> getStarredDocuments(User user) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.selectFrom(DOCUMENT.join(DOCUMENT_USER_STAR).on(DOCUMENT.ID.eq(DOCUMENT_USER_STAR.DOC_ID))).
                where(DOCUMENT_USER_STAR.USER_ID.eq(user.id)).fetch().stream().map(this::createDocumentFrom).collect(toList());
    }

    // ------------- functions that don't need document migration/indexing
    // they can use just the DOCUMENT_USER_STAR table thus denormalizing project information
    // this could be removed later
    @Override
    public int star(Project project, User user, List<String> documentIds) {
        InsertValuesStep3<DocumentUserStarRecord, String, String, String>
            query = using(connectionProvider, dialect).
                insertInto(DOCUMENT_USER_STAR, DOCUMENT_USER_STAR.DOC_ID, DOCUMENT_USER_STAR.USER_ID, DOCUMENT_USER_STAR.PRJ_ID);
        documentIds.forEach(t -> query.values(t, user.id, project.getId()));
        return query.onConflictDoNothing().execute();
    }

    @Override
    public int unstar(Project project, User user, List<String> documentIds) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_USER_STAR).
                where(DOCUMENT_USER_STAR.DOC_ID.in(documentIds),
                        DOCUMENT_USER_STAR.USER_ID.eq(user.id),
                        DOCUMENT_USER_STAR.PRJ_ID.eq(project.getId())).execute();
    }

    @Override
    public List<String> getStarredDocuments(Project project, User user) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.select(DOCUMENT_USER_STAR.DOC_ID).from(DOCUMENT_USER_STAR).
                where(DOCUMENT_USER_STAR.USER_ID.eq(user.id)).
                and(DOCUMENT_USER_STAR.PRJ_ID.eq(project.getId())).
                fetch().getValues(DOCUMENT_USER_STAR.DOC_ID);
    }

    @Override
    public int recommend(Project project, User user, List<String> documentIds) {
        InsertValuesStep4<DocumentUserRecommendationRecord, String, String, String, Timestamp> query = DSL.using(connectionProvider, dialect).
                insertInto(DOCUMENT_USER_RECOMMENDATION, DOCUMENT_USER_RECOMMENDATION.DOC_ID, DOCUMENT_USER_RECOMMENDATION.USER_ID, DOCUMENT_USER_RECOMMENDATION.PRJ_ID, DOCUMENT_USER_RECOMMENDATION.CREATION_DATE);
        Timestamp now = Timestamp.from(Instant.now());
        documentIds.forEach(t -> query.values(t, user.id, project.getId(), now));
        return query.execute();
    }

    @Override
    public int unrecommend(Project project, User user, List<String> documentIds) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_USER_RECOMMENDATION).
                where(DOCUMENT_USER_RECOMMENDATION.DOC_ID.in(documentIds),
                        DOCUMENT_USER_RECOMMENDATION.USER_ID.eq(user.id),
                        DOCUMENT_USER_RECOMMENDATION.PRJ_ID.eq(project.getId())).execute();
    }

    @Override
    public AggregateList<User> getRecommendations(Project project, List<String> documentIds) {
        DSLContext context = using(connectionProvider, dialect);
        return new AggregateList<>(
                createAggregateFromSelect(createSelectRecommendationLeftJoinInventory(context, project).and(DOCUMENT_USER_RECOMMENDATION.DOC_ID.in(documentIds))),
                selectCount(context, project).and(DOCUMENT_USER_RECOMMENDATION.DOC_ID.in(documentIds)).fetchOne(0, int.class)
        );
    }

    @Override
    public boolean addToUserHistory(List<Project> projects, UserEvent userEvent) {
        return using(connectionProvider, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            InsertValuesStep6<UserHistoryRecord, Timestamp, Timestamp, String, Short, String, String>
                insertHistory = inner.
                    insertInto(USER_HISTORY, USER_HISTORY.CREATION_DATE, USER_HISTORY.MODIFICATION_DATE,
                            USER_HISTORY.USER_ID, USER_HISTORY.TYPE, USER_HISTORY.NAME, USER_HISTORY.URI);
            insertHistory.values(new Timestamp(userEvent.creationDate.getTime()), new Timestamp(userEvent.modificationDate.getTime()),
                    userEvent.user.id, userEvent.type.id, userEvent.name, userEvent.uri.toString());
            UserHistoryRecord insertHistoryRecord = insertHistory.onConflict(USER_HISTORY.USER_ID, USER_HISTORY.URI)
                    .doUpdate()
                    .set(USER_HISTORY.MODIFICATION_DATE, new Timestamp(userEvent.modificationDate.getTime()))
                    .returning(USER_HISTORY.ID).fetchOne();

            if (insertHistoryRecord == null) {
                return false;
            }

            InsertValuesStep2<UserHistoryProjectRecord, Integer, String> insertProject = inner.
                    insertInto(USER_HISTORY_PROJECT, USER_HISTORY_PROJECT.USER_HISTORY_ID, USER_HISTORY_PROJECT.PRJ_ID);
            projects.forEach(project -> insertProject.values(insertHistoryRecord.getValue(USER_HISTORY.ID), project.getId()));
            return insertProject.onConflictDoNothing().execute() >= 0;
        });
    }

    @Override
    public List<UserEvent> getUserHistory(User user, UserEvent.Type type, int from, int size, String sort, boolean desc, String... projectIds) {
        String sortName = Optional.ofNullable(sort).filter(Predicate.not(String::isBlank)).orElse(USER_HISTORY.MODIFICATION_DATE.getName());
        Field<?> sortBy =  Optional.ofNullable(USER_HISTORY.field(sortName)).orElseThrow(() -> new IllegalArgumentException(String.format("Invalid sort attribute: %s", sortName)));
        SortField<?> order  = desc ? sortBy.desc() : sortBy.asc();
        if (projectIds.length>0) {
            return using(connectionProvider, dialect)
                    .selectFrom(USER_HISTORY)
                    .where(USER_HISTORY.USER_ID.eq(user.id)).and(USER_HISTORY.TYPE.eq(type.id))
                    .and(USER_HISTORY.ID
                            .in(select(USER_HISTORY_PROJECT.USER_HISTORY_ID).from(USER_HISTORY_PROJECT).where(USER_HISTORY_PROJECT.PRJ_ID.in(projectIds)))
                    )
                    .orderBy(order).offset(from).limit(size).stream().map(this::createUserEventFrom).collect(toList());
        } else {
            return using(connectionProvider, dialect).selectFrom(USER_HISTORY).
                    where(USER_HISTORY.USER_ID.eq(user.id)).and(USER_HISTORY.TYPE.eq(type.id))
                    .orderBy(order).offset(from).limit(size).stream().map(this::createUserEventFrom).collect(toList());
        }

    }

    @Override
    public int getUserHistorySize(User user, UserEvent.Type type, String... projectIds) {
        SelectConditionStep<Record1<Integer>>
            query = using(connectionProvider, dialect).selectCount().from(USER_HISTORY).
                where(USER_HISTORY.USER_ID.eq(user.id)).and(USER_HISTORY.TYPE.eq(type.id));
        if(projectIds.length>0){
            query.and(USER_HISTORY.ID.in(select(USER_HISTORY_PROJECT.USER_HISTORY_ID).from(USER_HISTORY_PROJECT).where(USER_HISTORY_PROJECT.PRJ_ID.in(projectIds))));
        }
        return query.fetchOne(0, int.class);

    }

    @Override
    public boolean deleteUserHistory(User user, UserEvent.Type type) {
        return using(connectionProvider, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.deleteFrom(USER_HISTORY_PROJECT).
                    where(USER_HISTORY_PROJECT.USER_HISTORY_ID.in(
                            select(USER_HISTORY.ID)
                                    .from(USER_HISTORY)
                                    .where(USER_HISTORY.TYPE.eq(type.id)).and(USER_HISTORY.USER_ID.eq(user.id))
                    )).execute();
            return inner.deleteFrom(USER_HISTORY).
                    where(USER_HISTORY.USER_ID.eq(user.id)).and(USER_HISTORY.TYPE.eq(type.id)).execute() > 0;
        });
    }

    @Override
    public boolean deleteUserHistoryEvent(User user, int eventId) {
        return using(connectionProvider, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.deleteFrom(USER_HISTORY_PROJECT).
                    where(USER_HISTORY_PROJECT.USER_HISTORY_ID.eq(eventId)).execute();
            return inner.deleteFrom(USER_HISTORY).
                    where(USER_HISTORY.USER_ID.eq(user.id)).and(USER_HISTORY.ID.eq(eventId)).execute() > 0;
        });
    }

    @Override
    public boolean renameSavedSearch(User user, int eventId, String newName) {
        return using(connectionProvider, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            UpdateConditionStep<UserHistoryRecord> updatedTuple = inner.update(USER_HISTORY)
                    .set(USER_HISTORY.NAME, newName)
                    .where(USER_HISTORY.USER_ID.eq(user.id))
                    .and(USER_HISTORY.ID.eq(eventId))
                    .and(USER_HISTORY.TYPE.eq(UserEvent.Type.SEARCH.id));

            return updatedTuple.execute() > 0;
        });
    }

    @Override
    public AggregateList<User> getRecommendations(Project project) {
        try(DSLContext context = using(connectionProvider, dialect)) {
            return new AggregateList<>(
                    createAggregateFromSelect(createSelectRecommendationLeftJoinInventory(context, project)),
                    selectCount(context, project).fetchOne(0, int.class)
            );
        }
    }

    @Override
    public Set<String> getRecommentationsBy(Project project, List<User> users){
        try(DSLContext create = DSL.using(connectionProvider,dialect)) {
            return create.select(DOCUMENT_USER_RECOMMENDATION.DOC_ID).from(DOCUMENT_USER_RECOMMENDATION)
                    .where(DOCUMENT_USER_RECOMMENDATION.USER_ID.in(users.stream().map(x -> x.id).collect(toList())))
                    .and(DOCUMENT_USER_RECOMMENDATION.PRJ_ID.eq(project.getId()))
                    .fetch().getValues(DOCUMENT_USER_RECOMMENDATION.DOC_ID).stream().collect(Collectors.toSet());
        }
    }

    @Override
    public boolean tag(Project prj, String documentId, Tag... tags) {
        InsertValuesStep5<DocumentTagRecord, String, String, String, Timestamp, String> query = using(connectionProvider, dialect).insertInto(
                DOCUMENT_TAG, DOCUMENT_TAG.DOC_ID, DOCUMENT_TAG.LABEL, DOCUMENT_TAG.PRJ_ID,
                DOCUMENT_TAG.CREATION_DATE, DOCUMENT_TAG.USER_ID);
        List<Tag> tagList = asList(tags);
        tagList.forEach(t -> query.values(documentId, t.label, prj.getId(), new Timestamp(t.creationDate.getTime()), t.user.id));
        return query.onConflictDoNothing().execute() > 0;
    }

    @Override
    public boolean untag(Project prj, String documentId, Tag... tags) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_TAG).
                where(DOCUMENT_TAG.DOC_ID.eq(documentId),
                        DOCUMENT_TAG.LABEL.in(stream(tags).map(t -> t.label).collect(toSet())),
                        DOCUMENT_TAG.PRJ_ID.eq(prj.getId())).execute() > 0;
    }

    @Override
    public boolean tag(Project prj, List<String> documentIds, Tag... tags) {
        InsertValuesStep5<DocumentTagRecord, String, String, String, Timestamp, String> query = using(connectionProvider, dialect).insertInto(
                DOCUMENT_TAG, DOCUMENT_TAG.DOC_ID, DOCUMENT_TAG.LABEL, DOCUMENT_TAG.PRJ_ID,
                DOCUMENT_TAG.CREATION_DATE, DOCUMENT_TAG.USER_ID);
        List<Tag> tagList = asList(tags);
        documentIds.forEach(d -> tagList.forEach(t -> query.values(d, t.label, prj.getId(), new Timestamp(t.creationDate.getTime()), t.user.id)));
        return query.onConflictDoNothing().execute() > 0;
    }

    @Override
    public boolean untag(Project prj, List<String> documentIds, Tag... tags) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_TAG).
                where(DOCUMENT_TAG.DOC_ID.in(documentIds),
                        DOCUMENT_TAG.LABEL.in(stream(tags).map(t -> t.label).collect(toSet())),
                        DOCUMENT_TAG.PRJ_ID.eq(prj.getId())).execute() > 0;
    }

    @Override
    public List<String> getDocuments(Project project, Tag... tags) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.selectDistinct(DOCUMENT_TAG.DOC_ID).from(DOCUMENT_TAG).
                where(DOCUMENT_TAG.LABEL.in(stream(tags).map(t -> t.label).collect(toSet()))).
                and(DOCUMENT_TAG.PRJ_ID.eq(project.getId())).
                fetch().getValues(DOCUMENT_TAG.DOC_ID);
    }

    @Override
    public List<Tag> getTags(Project project, String documentId) {
        return DSL.using(connectionProvider, dialect).selectFrom(DOCUMENT_TAG).
                where(DOCUMENT_TAG.DOC_ID.eq(documentId)).and(DOCUMENT_TAG.PRJ_ID.eq(project.getId())).
                stream().map(this::createTagFrom).collect(toList());
    }

    @Override
    public boolean deleteAll(String projectId) {
        return DSL.using(connectionProvider, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            int deleteTagResult = inner.deleteFrom(DOCUMENT_TAG).where(DOCUMENT_TAG.PRJ_ID.eq(projectId)).execute();
            int deleteStarResult = inner.deleteFrom(DOCUMENT_USER_STAR).where(DOCUMENT_USER_STAR.PRJ_ID.eq(projectId)).execute();
            int deleteUserRecommendationResult = inner.deleteFrom(DOCUMENT_USER_RECOMMENDATION).where(DOCUMENT_USER_RECOMMENDATION.PRJ_ID.eq(projectId)).execute();
            int deleteUserHistoryResult = inner.deleteFrom(USER_HISTORY_PROJECT).where(USER_HISTORY_PROJECT.PRJ_ID.eq(projectId)).execute();
            int deleteProject = inner.deleteFrom(PROJECT).where(PROJECT.ID.eq(projectId)).execute();
            return deleteStarResult + deleteTagResult + deleteUserRecommendationResult + deleteUserHistoryResult + deleteProject > 0;
        });
    }

    @Override
    public Project getProject(String projectId) {
        return createProjectFrom(DSL.using(connectionProvider, dialect).selectFrom(PROJECT).
                where(PROJECT.ID.eq(projectId)).fetchOne());
    }

    @Override
    public List<Project> getProjects() {
        return DSL.using(connectionProvider, dialect).selectFrom(PROJECT).
                stream().map(this::createProjectFrom).collect(toList());
    }

    @Override
    public List<Project> getProjects(List<String> projectIds) {
        return DSL.using(connectionProvider, dialect).selectFrom(PROJECT).
                where(PROJECT.ID.in(projectIds)).
                stream().map(this::createProjectFrom).collect(toList());
    }

    @Override
    public List<Note> getNotes(Project prj, String documentPath) {
        return DSL.using(connectionProvider, dialect).selectFrom(NOTE).
                where(NOTE.PROJECT_ID.eq(prj.getId())).and(value(documentPath).like(NOTE.PATH.concat('%'))).
                stream().map(this::createNoteFrom).collect(toList());
    }

    @Override
    public List<Note> getNotes(Project prj) {
        return DSL.using(connectionProvider, dialect).selectFrom(NOTE).
                where(NOTE.PROJECT_ID.eq(prj.getId())).
                stream().map(this::createNoteFrom).collect(toList());
    }

    @Override
    public List<DocumentUserRecommendation> getDocumentUserRecommendations(int from, int size, List<Project> projects) {
        List<String> projectIds = projects.stream().map(Project::getId).collect(toList());
        try (DSLContext dsl = DSL.using(connectionProvider, dialect)) {
            return createSelectDocumentUserRecommendations(dsl)
                    .where(DOCUMENT_USER_RECOMMENDATION.PRJ_ID.in(projectIds))
                    .orderBy(DOCUMENT_USER_RECOMMENDATION.CREATION_DATE.desc())
                    .limit(size)
                    .offset(from)
                    .stream()
                    .map(this::createDocumentUserRecommendationFrom)
                    .collect(toList());
        }
    }

    @Override
    public List<DocumentUserRecommendation> getDocumentUserRecommendations(int from, int size) {
        try (DSLContext dsl = DSL.using(connectionProvider, dialect)) {
            return createSelectDocumentUserRecommendations(dsl)
                    .orderBy(DOCUMENT_USER_RECOMMENDATION.CREATION_DATE.desc())
                    .limit(size)
                    .offset(from)
                    .stream()
                    .map(this::createDocumentUserRecommendationFrom)
                    .collect(toList());
        }
    }

    @Override
    public boolean save(Note note) {
        return DSL.using(connectionProvider, dialect).insertInto(NOTE, NOTE.PROJECT_ID, NOTE.PATH, NOTE.NOTE_, NOTE.VARIANT).
                values(note.project.name, note.path.toString(), note.note, note.variant.name()).execute() > 0;
    }

    @Override
    public boolean save(Project project) {
        Timestamp projectCreationDate =  project.creationDate == null ? null: new Timestamp(project.creationDate.getTime());
        Timestamp projectUpdateDate =  project.updateDate == null ? null: new Timestamp(project.updateDate.getTime());
        return DSL.using(connectionProvider, dialect).
                insertInto(
                        PROJECT, PROJECT.ID, PROJECT.LABEL, PROJECT.DESCRIPTION, PROJECT.PATH, PROJECT.SOURCE_URL,
                        PROJECT.MAINTAINER_NAME, PROJECT.PUBLISHER_NAME, PROJECT.LOGO_URL,
                        PROJECT.ALLOW_FROM_MASK,
                        PROJECT.CREATION_DATE, PROJECT.UPDATE_DATE).
                values(
                        project.name, project.label, project.description, project.sourcePath.toString(), project.sourceUrl,
                        project.maintainerName, project.publisherName, project.logoUrl,
                        project.allowFromMask,
                        projectCreationDate, projectUpdateDate).
                onConflict(PROJECT.ID).
                    doUpdate().
                        set(PROJECT.LABEL, project.label).
                        set(PROJECT.DESCRIPTION, project.description).
                        set(PROJECT.SOURCE_URL, project.sourceUrl).
                        set(PROJECT.MAINTAINER_NAME, project.maintainerName).
                        set(PROJECT.PUBLISHER_NAME, project.publisherName).
                        set(PROJECT.LOGO_URL, project.logoUrl).
                        set(PROJECT.ALLOW_FROM_MASK, project.allowFromMask).
                        set(PROJECT.UPDATE_DATE, projectUpdateDate).
                execute() > 0;
    }

    public boolean save(User user) {
        return DSL.using(connectionProvider, dialect).
                insertInto(
                        USER_INVENTORY, USER_INVENTORY.ID, USER_INVENTORY.EMAIL,
                        USER_INVENTORY.NAME, USER_INVENTORY.PROVIDER, USER_INVENTORY.DETAILS).
                values(user.id, user.email, user.name, user.provider, JsonUtils.serialize(user.details)).
                onConflict(USER_INVENTORY.ID).
                    doUpdate().
                        set(USER_INVENTORY.EMAIL, user.email).
                        set(USER_INVENTORY.DETAILS, JsonUtils.serialize(user.details)).
                        set(USER_INVENTORY.NAME, user.name).
                        set(USER_INVENTORY.PROVIDER, user.provider).
                execute() > 0;
    }

    public User getUser(String uid) {
        return createUserFrom(DSL.using(connectionProvider, dialect).selectFrom(USER_INVENTORY).where(USER_INVENTORY.ID.eq(uid)).fetchOne());
    }

    @Override
    public boolean getHealth(){
        try {
            return DSL.using(connectionProvider, dialect).select().fetchOne().toString().contains("1");
        } catch (DataAccessException ex){
            LoggerFactory.getLogger(getClass()).error("Database Health error : ",ex);
            return false;
        }
    }

    private SelectConditionStep<Record1<Integer>> selectCount(DSLContext context, Project project) {
        return context.select(countDistinct(DOCUMENT_USER_RECOMMENDATION.DOC_ID))
                .from(DOCUMENT_USER_RECOMMENDATION)
                .where(DOCUMENT_USER_RECOMMENDATION.PRJ_ID.eq(project.getId()));
    }

    private List<Aggregate<User>> createAggregateFromSelect(SelectConditionStep<Record> select) {
        return select.groupBy(DOCUMENT_USER_RECOMMENDATION.USER_ID, USER_INVENTORY.ID).
                fetch().stream().map(r -> new Aggregate<>(createUserFrom(r), r.get("count", Integer.class))).
                collect(toList());
    }

    private SelectConditionStep<Record> createSelectRecommendationLeftJoinInventory(DSLContext create, Project project) {
        return create.select(DOCUMENT_USER_RECOMMENDATION.USER_ID, USER_INVENTORY.asterisk(), count()).from(DOCUMENT_USER_RECOMMENDATION.
                leftJoin(USER_INVENTORY).on(DOCUMENT_USER_RECOMMENDATION.USER_ID.eq(USER_INVENTORY.ID))).
                where(DOCUMENT_USER_RECOMMENDATION.PRJ_ID.eq(project.getId()));
    }

    private SelectJoinStep<Record> createSelectDocumentUserRecommendations(DSLContext dsl) {
        return dsl
            .select()
            .from(DOCUMENT_USER_RECOMMENDATION)
            .leftJoin(USER_INVENTORY)
                .on(DOCUMENT_USER_RECOMMENDATION.USER_ID.eq(USER_INVENTORY.ID))
            .join(PROJECT)
                .on(DOCUMENT_USER_RECOMMENDATION.PRJ_ID.eq(PROJECT.ID));
    }

    private User createUserFrom(Record record) {
        if (record == null) {
            return null;
        }
        UserInventoryRecord userRecord = record.into(USER_INVENTORY);
        if (userRecord.getId() == null) {
            return new User(record.into(DOCUMENT_USER_RECOMMENDATION).getUserId());
        }
        return new User(userRecord.getId(), userRecord.getName(), userRecord.getEmail(), userRecord.getProvider(), userRecord.getDetails());
    }

    private Note createNoteFrom(NoteRecord noteRecord) {
        if (noteRecord == null) {
            return null;
        }
        return new Note(project(noteRecord.getProjectId()),
                Paths.get(noteRecord.getPath()),
                noteRecord.getNote(),
                Note.Variant.valueOf(noteRecord.getVariant()));
    }

    private Project createProjectFrom(ProjectRecord record) {
        if (record == null) {
            return null;
        }
        Timestamp projectCreationDate =  record.getCreationDate() == null ? null: new Timestamp(record.getCreationDate().getTime());
        Timestamp projectUpdateDate =  record.getUpdateDate() == null ? null: new Timestamp(record.getCreationDate().getTime());

        return new Project(record.getId(),
                record.getLabel(),
                record.getDescription(),
                Paths.get(record.getPath()),
                record.getSourceUrl(),
                record.getMaintainerName(),
                record.getPublisherName(),
                record.getLogoUrl(),
                record.getAllowFromMask(),
                projectCreationDate,
                projectUpdateDate);
    }

    private DocumentUserRecommendation createDocumentUserRecommendationFrom(Record record) {
        if (record == null) {
            return null;
        }

        DocumentUserRecommendationRecord recommendation = record.into(DOCUMENT_USER_RECOMMENDATION);
        Project project = createProjectFrom(record.into(PROJECT));
        ProjectProxy projectProxy = new ProjectProxy(project.getName());
        Document document = DocumentBuilder.createDoc().withId(recommendation.getDocId()).with(project).build();
        UserInventoryRecord userHistoryRecord = record.into(USER_INVENTORY);
        // Since the userHistoryRecord is queried through a left join, it can be empty.
        // In that case, we need to override id manually to avoid build an empty User instance.
        if (userHistoryRecord.get(USER_INVENTORY.ID) == null) {
            userHistoryRecord.setValue(USER_INVENTORY.ID, recommendation.getUserId());
            userHistoryRecord.setValue(USER_INVENTORY.DETAILS, "{}");
        }
        User user = createUserFrom(userHistoryRecord);
        Timestamp creationDate =  recommendation.getCreationDate() == null ? null : new Timestamp(recommendation.getCreationDate().getTime());
        return new DocumentUserRecommendation(document, projectProxy, user, creationDate);
    }

    private NamedEntity createFrom(NamedEntityRecord record) {
        try {
            return NamedEntity.create(NamedEntity.Category.parse(record.getCategory()),
                    record.getMention(), MAPPER.readValue(record.getOffsets(), List.class),
                    record.getDocId(), record.getRootId(), Pipeline.Type.fromCode(record.getExtractor()),
                    Language.parse(record.getExtractorLanguage()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Document createDocumentFrom(Record result) {
        DocumentRecord documentRecord = result.into(DOCUMENT);
        Map<String, Object> metadata;
        try {
            // type check
            metadata = MAPPER.readValue(documentRecord.getMetadata(), HashMap.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Pipeline.Type[] nerTags = Document.fromNerMask(documentRecord.getNerMask()).toArray(Pipeline.Type[]::new);
        return DocumentBuilder.createDoc()
                .with(project(documentRecord.getProjectId()))
                .with(Document.Status.PARSED)
                .withId(documentRecord.getId())
                .with(Paths.get(documentRecord.getPath()))
                .with(documentRecord.getContent())
                .with(parse(documentRecord.getLanguage()))
                .with(forName(documentRecord.getCharset()))
                .ofMimeType(documentRecord.getContentType())
                .with(metadata)
                .with(nerTags)
                .with(fromCode(documentRecord.getStatus()))
                .extractedAt(new Date(documentRecord.getExtractionDate().getTime()))
                .withParentId(documentRecord.getParentId())
                .withRootId(documentRecord.getRootId())
                .withExtractionLevel(documentRecord.getExtractionLevel())
                .withContentLength(documentRecord.getContentLength())
                .build();
    }

    private Tag createTagFrom(DocumentTagRecord record) {
        return new Tag(record.getLabel(), new User(record.getUserId()), new Date(record.getCreationDate().getTime()));
    }

    private UserEvent createUserEventFrom(UserHistoryRecord record) {
        if (record == null) {
            return null;
        } else {
            UserEvent userEvent;
            try {
                userEvent = new UserEvent(record.getId(), new User(record.getUserId()), fromId(record.getType()),
                        record.getName(), new URI(record.getUri()), new Date(record.getCreationDate().getTime()),
                        new Date(record.getModificationDate().getTime()));
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            return userEvent;
        }
    }
}
