/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.rule;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.picocontainer.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.Rule;
import org.sonar.api.server.debt.DebtRemediationFunction;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.utils.MessageException;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.TimeProfiler;
import org.sonar.check.Cardinality;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.qualityprofile.db.ActiveRuleDao;
import org.sonar.core.rule.RuleDao;
import org.sonar.core.rule.RuleDto;
import org.sonar.core.rule.RuleParamDto;
import org.sonar.core.rule.RuleRuleTagDto;
import org.sonar.core.rule.RuleTagDao;
import org.sonar.core.rule.RuleTagDto;
import org.sonar.core.rule.RuleTagType;
import org.sonar.core.technicaldebt.db.CharacteristicDao;
import org.sonar.core.technicaldebt.db.CharacteristicDto;
import org.sonar.server.qualityprofile.ProfilesManager;
import org.sonar.server.startup.RegisterDebtModel;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Register rules at server startup
 *
 * @since 4.2
 */
public class RegisterRules implements Startable {

  private static final Logger LOG = LoggerFactory.getLogger(RegisterRules.class);

  private final RuleDefinitionsLoader defLoader;
  private final ProfilesManager profilesManager;
  private final RuleRegistry ruleRegistry;
  private final ESRuleTags esRuleTags;
  private final MyBatis myBatis;
  private final RuleDao ruleDao;
  private final RuleTagDao ruleTagDao;
  private final RuleTagOperations ruleTagOperations;
  private final ActiveRuleDao activeRuleDao;
  private final CharacteristicDao characteristicDao;
  private final System2 system;

  /**
   * @param registerDebtModel used only to be started after init of the technical debt model
   */
  public RegisterRules(RuleDefinitionsLoader defLoader, ProfilesManager profilesManager,
                       RuleRegistry ruleRegistry, ESRuleTags esRuleTags, RuleTagOperations ruleTagOperations,
                       MyBatis myBatis, RuleDao ruleDao, RuleTagDao ruleTagDao, ActiveRuleDao activeRuleDao, CharacteristicDao characteristicDao,
                       RegisterDebtModel registerDebtModel) {
    this(defLoader, profilesManager, ruleRegistry, esRuleTags, ruleTagOperations, myBatis, ruleDao, ruleTagDao, activeRuleDao, characteristicDao, System2.INSTANCE);
  }

  @VisibleForTesting
  RegisterRules(RuleDefinitionsLoader defLoader, ProfilesManager profilesManager,
                RuleRegistry ruleRegistry, ESRuleTags esRuleTags, RuleTagOperations ruleTagOperations,
                MyBatis myBatis, RuleDao ruleDao, RuleTagDao ruleTagDao, ActiveRuleDao activeRuleDao, CharacteristicDao characteristicDao, System2 system) {
    this.defLoader = defLoader;
    this.profilesManager = profilesManager;
    this.ruleRegistry = ruleRegistry;
    this.esRuleTags = esRuleTags;
    this.ruleTagOperations = ruleTagOperations;
    this.myBatis = myBatis;
    this.ruleDao = ruleDao;
    this.ruleTagDao = ruleTagDao;
    this.activeRuleDao = activeRuleDao;
    this.characteristicDao = characteristicDao;
    this.system = system;
  }

  @Override
  public void start() {
    TimeProfiler profiler = new TimeProfiler().start("Register rules");
    DbSession sqlSession = myBatis.openSession(false);
    try {
      RulesDefinition.Context context = defLoader.load();
      Buffer buffer = new Buffer(system.now());
      selectRulesFromDb(buffer, sqlSession);
      enableRuleDefinitions(context, buffer, sqlSession);
      List<RuleDto> removedRules = processRemainingDbRules(buffer, sqlSession);
      removeActiveRulesOnStillExistingRepositories(removedRules, context);
      index(buffer, sqlSession);
      ruleTagOperations.deleteUnusedTags(sqlSession);
      sqlSession.commit();

    } finally {
      sqlSession.close();
      profiler.stop();
    }
  }

  @Override
  public void stop() {
    // nothing
  }

  private void selectRulesFromDb(Buffer buffer, DbSession sqlSession) {
    for (RuleDto ruleDto : ruleDao.selectNonManual(sqlSession)) {
      buffer.add(ruleDto);
      buffer.markUnprocessed(ruleDto);
    }
    for (RuleParamDto paramDto : ruleDao.selectParameters(sqlSession)) {
      buffer.add(paramDto);
    }
    for (RuleTagDto tagDto : ruleTagDao.selectAll(sqlSession)) {
      buffer.add(tagDto);
    }
    for (RuleRuleTagDto tagDto : ruleDao.selectTags(sqlSession)) {
      buffer.add(tagDto);
    }
    for (CharacteristicDto characteristicDto : characteristicDao.selectEnabledCharacteristics(sqlSession)) {
      buffer.add(characteristicDto);
    }
  }

  private void enableRuleDefinitions(RulesDefinition.Context context, Buffer buffer, DbSession sqlSession) {
    for (RulesDefinition.Repository repoDef : context.repositories()) {
      enableRepository(buffer, sqlSession, repoDef);
    }
    for (RulesDefinition.ExtendedRepository extendedRepoDef : context.extendedRepositories()) {
      if (context.repository(extendedRepoDef.key()) == null) {
        LOG.warn(String.format("Extension is ignored, repository %s does not exist", extendedRepoDef.key()));
      } else {
        enableRepository(buffer, sqlSession, extendedRepoDef);
      }
    }
  }

  private void enableRepository(Buffer buffer, DbSession sqlSession, RulesDefinition.ExtendedRepository repoDef) {
    int count = 0;
    for (RulesDefinition.Rule ruleDef : repoDef.rules()) {
      RuleDto dto = buffer.rule(RuleKey.of(ruleDef.repository().key(), ruleDef.key()));
      if (dto == null) {
        dto = enableAndInsert(buffer, sqlSession, ruleDef);
      } else {
        enableAndUpdate(buffer, sqlSession, ruleDef, dto);
      }
      buffer.markProcessed(dto);
      count++;
      if (count % 100 == 0) {
        sqlSession.commit();
      }
    }
    sqlSession.commit();
  }

  private RuleDto enableAndInsert(Buffer buffer, DbSession sqlSession, RulesDefinition.Rule ruleDef) {
    RuleDto ruleDto = new RuleDto()
      .setCardinality(ruleDef.template() ? Cardinality.MULTIPLE : Cardinality.SINGLE)
      .setConfigKey(ruleDef.internalKey())
      .setDescription(ruleDef.htmlDescription())
      .setLanguage(ruleDef.repository().language())
      .setName(ruleDef.name())
      .setRepositoryKey(ruleDef.repository().key())
      .setRuleKey(ruleDef.key())
      .setSeverity(ruleDef.severity())
      .setCreatedAt(buffer.now())
      .setUpdatedAt(buffer.now())
      .setStatus(ruleDef.status().name());

    CharacteristicDto characteristic = buffer.characteristic(ruleDef.debtSubCharacteristic(), ruleDef.repository().key(), ruleDef.key(), null);
    DebtRemediationFunction remediationFunction = ruleDef.debtRemediationFunction();
    if (characteristic != null && remediationFunction != null) {
      ruleDto.setDefaultSubCharacteristicId(characteristic.getId())
        .setDefaultRemediationFunction(remediationFunction.type().name())
        .setDefaultRemediationCoefficient(remediationFunction.coefficient())
        .setDefaultRemediationOffset(remediationFunction.offset())
        .setEffortToFixDescription(ruleDef.effortToFixDescription());
    }

    ruleDao.insert(ruleDto, sqlSession);
    buffer.add(ruleDto);

    for (RulesDefinition.Param param : ruleDef.params()) {
      RuleParamDto paramDto = new RuleParamDto()
        .setRuleId(ruleDto.getId())
        .setDefaultValue(param.defaultValue())
        .setDescription(param.description())
        .setName(param.name())
        .setType(param.type().toString());
      ruleDao.insert(paramDto, sqlSession);
      buffer.add(paramDto);
    }
    mergeTags(buffer, sqlSession, ruleDef, ruleDto);
    return ruleDto;
  }

  private void enableAndUpdate(Buffer buffer, DbSession sqlSession, RulesDefinition.Rule ruleDef, RuleDto dto) {
    if (mergeRule(buffer, ruleDef, dto)) {
      ruleDao.update(dto, sqlSession);
    }
    mergeParams(buffer, sqlSession, ruleDef, dto);
    mergeTags(buffer, sqlSession, ruleDef, dto);
    buffer.markProcessed(dto);
  }

  private boolean mergeRule(Buffer buffer, RulesDefinition.Rule def, RuleDto dto) {
    boolean changed = false;
    if (!StringUtils.equals(dto.getName(), def.name())) {
      dto.setName(def.name());
      changed = true;
    }
    if (!StringUtils.equals(dto.getDescription(), def.htmlDescription())) {
      dto.setDescription(def.htmlDescription());
      changed = true;
    }
    if (!StringUtils.equals(dto.getConfigKey(), def.internalKey())) {
      dto.setConfigKey(def.internalKey());
      changed = true;
    }
    String severity = def.severity();
    if (!ObjectUtils.equals(dto.getSeverityString(), severity)) {
      dto.setSeverity(severity);
      changed = true;
    }
    Cardinality cardinality = def.template() ? Cardinality.MULTIPLE : Cardinality.SINGLE;
    if (!cardinality.equals(dto.getCardinality())) {
      dto.setCardinality(cardinality);
      changed = true;
    }
    String status = def.status().name();
    if (!StringUtils.equals(dto.getStatus(), status)) {
      dto.setStatus(status);
      changed = true;
    }
    if (!StringUtils.equals(dto.getLanguage(), def.repository().language())) {
      dto.setLanguage(def.repository().language());
      changed = true;
    }
    CharacteristicDto subCharacteristic = buffer.characteristic(def.debtSubCharacteristic(), def.repository().key(), def.key(), dto.getSubCharacteristicId());
    changed = mergeDebtDefinitions(def, dto, subCharacteristic) || changed;
    if (changed) {
      dto.setUpdatedAt(buffer.now());
    }
    return changed;
  }

  private boolean mergeDebtDefinitions(RulesDefinition.Rule def, RuleDto dto, @Nullable CharacteristicDto subCharacteristic) {
    // Debt definitions are set to null if the sub-characteristic and the remediation function are null
    DebtRemediationFunction debtRemediationFunction = subCharacteristic != null ? def.debtRemediationFunction() : null;
    boolean hasDebt = subCharacteristic != null && debtRemediationFunction != null;
    return mergeDebtDefinitions(def, dto,
      hasDebt ? subCharacteristic.getId() : null,
      debtRemediationFunction != null ? debtRemediationFunction.type().name() : null,
      hasDebt ? debtRemediationFunction.coefficient() : null,
      hasDebt ? debtRemediationFunction.offset() : null,
      hasDebt ? def.effortToFixDescription() : null);
  }

  private boolean mergeDebtDefinitions(RulesDefinition.Rule def, RuleDto dto, @Nullable Integer characteristicId, @Nullable String remediationFunction,
                                       @Nullable String remediationCoefficient, @Nullable String remediationOffset, @Nullable String effortToFixDescription) {
    boolean changed = false;

    if (!ObjectUtils.equals(dto.getDefaultSubCharacteristicId(), characteristicId)) {
      dto.setDefaultSubCharacteristicId(characteristicId);
      changed = true;
    }
    if (!StringUtils.equals(dto.getDefaultRemediationFunction(), remediationFunction)) {
      dto.setDefaultRemediationFunction(remediationFunction);
      changed = true;
    }
    if (!StringUtils.equals(dto.getDefaultRemediationCoefficient(), remediationCoefficient)) {
      dto.setDefaultRemediationCoefficient(remediationCoefficient);
      changed = true;
    }
    if (!StringUtils.equals(dto.getDefaultRemediationOffset(), remediationOffset)) {
      dto.setDefaultRemediationOffset(remediationOffset);
      changed = true;
    }
    if (!StringUtils.equals(dto.getEffortToFixDescription(), effortToFixDescription)) {
      dto.setEffortToFixDescription(effortToFixDescription);
      changed = true;
    }
    return changed;
  }

  private void mergeParams(Buffer buffer, DbSession sqlSession, RulesDefinition.Rule ruleDef, RuleDto dto) {
    Collection<RuleParamDto> paramDtos = buffer.paramsForRuleId(dto.getId());
    Set<String> persistedParamKeys = Sets.newHashSet();
    for (RuleParamDto paramDto : paramDtos) {
      RulesDefinition.Param paramDef = ruleDef.param(paramDto.getName());
      if (paramDef == null) {
        activeRuleDao.deleteParametersWithParamId(paramDto.getId(), sqlSession);
        ruleDao.deleteParam(paramDto, sqlSession);
      } else {
        // TODO validate that existing active rules still match constraints
        // TODO store param name
        if (mergeParam(paramDto, paramDef)) {
          ruleDao.update(paramDto, sqlSession);
        }
        persistedParamKeys.add(paramDto.getName());
      }
    }
    for (RulesDefinition.Param param : ruleDef.params()) {
      if (!persistedParamKeys.contains(param.key())) {
        RuleParamDto paramDto = new RuleParamDto()
          .setRuleId(dto.getId())
          .setName(param.key())
          .setDescription(param.description())
          .setDefaultValue(param.defaultValue())
          .setType(param.type().toString());
        ruleDao.insert(paramDto, sqlSession);
        buffer.add(paramDto);
      }
    }
  }

  private boolean mergeParam(RuleParamDto paramDto, RulesDefinition.Param paramDef) {
    boolean changed = false;
    if (!StringUtils.equals(paramDto.getType(), paramDef.type().toString())) {
      paramDto.setType(paramDef.type().toString());
      changed = true;
    }
    if (!StringUtils.equals(paramDto.getDefaultValue(), paramDef.defaultValue())) {
      paramDto.setDefaultValue(paramDef.defaultValue());
      changed = true;
    }
    if (!StringUtils.equals(paramDto.getDescription(), paramDef.description())) {
      paramDto.setDescription(paramDef.description());
      changed = true;
    }
    return changed;
  }

  private void mergeTags(Buffer buffer, DbSession sqlSession, RulesDefinition.Rule ruleDef, RuleDto dto) {
    Set<String> existingSystemTags = Sets.newHashSet();

    Collection<RuleRuleTagDto> tagDtos = ImmutableList.copyOf(buffer.tagsForRuleId(dto.getId()));
    for (RuleRuleTagDto tagDto : tagDtos) {
      String tag = tagDto.getTag();

      if (tagDto.getType() == RuleTagType.SYSTEM) {
        // tag previously declared by plugin
        if (!ruleDef.tags().contains(tag)) {
          // not declared anymore
          ruleDao.deleteTag(tagDto, sqlSession);
          buffer.remove(tagDto);
        } else {
          existingSystemTags.add(tagDto.getTag());
        }
      } else {
        // tags created by end-users
        if (ruleDef.tags().contains(tag)) {
          long tagId = getOrCreateReferenceTagId(buffer, tag, sqlSession);
          tagDto.setId(tagId);
          tagDto.setType(RuleTagType.SYSTEM);
          ruleDao.update(tagDto, sqlSession);
          existingSystemTags.add(tag);
        }
      }
    }

    for (String tag : ruleDef.tags()) {
      if (!existingSystemTags.contains(tag)) {
        long tagId = getOrCreateReferenceTagId(buffer, tag, sqlSession);
        RuleRuleTagDto newTagDto = new RuleRuleTagDto()
          .setRuleId(dto.getId())
          .setTagId(tagId)
          .setTag(tag)
          .setType(RuleTagType.SYSTEM);
        ruleDao.insert(newTagDto, sqlSession);
        buffer.add(newTagDto);
      }
    }
  }

  private long getOrCreateReferenceTagId(Buffer buffer, String tag, DbSession sqlSession) {
    // End-user tag is converted to system tag
    long tagId = 0L;
    if (buffer.referenceTagExists(tag)) {
      tagId = buffer.referenceTagIdForValue(tag);
    } else {
      RuleTagDto newRuleTag = new RuleTagDto().setTag(tag);
      ruleTagDao.insert(newRuleTag, sqlSession);
      buffer.add(newRuleTag);
      tagId = newRuleTag.getId();
    }
    return tagId;
  }

  private List<RuleDto> processRemainingDbRules(Buffer buffer, DbSession sqlSession) {
    List<RuleDto> removedRules = newArrayList();
    for (Integer unprocessedRuleId : buffer.unprocessedRuleIds) {
      RuleDto ruleDto = buffer.rulesById.get(unprocessedRuleId);
      boolean toBeRemoved = true;
      // Update custom rules from template
      if (ruleDto.getParentId() != null) {
        RuleDto parent = buffer.rulesById.get(ruleDto.getParentId());
        if (parent != null && !Rule.STATUS_REMOVED.equals(parent.getStatus())) {
          ruleDto.setLanguage(parent.getLanguage());
          ruleDto.setStatus(parent.getStatus());
          ruleDto.setDefaultSubCharacteristicId(parent.getDefaultSubCharacteristicId());
          ruleDto.setDefaultRemediationFunction(parent.getDefaultRemediationFunction());
          ruleDto.setDefaultRemediationCoefficient(parent.getDefaultRemediationCoefficient());
          ruleDto.setDefaultRemediationOffset(parent.getDefaultRemediationOffset());
          ruleDto.setEffortToFixDescription(parent.getEffortToFixDescription());
          ruleDto.setUpdatedAt(buffer.now());
          ruleDao.update(ruleDto, sqlSession);
          toBeRemoved = false;
        }
      }
      if (toBeRemoved && !Rule.STATUS_REMOVED.equals(ruleDto.getStatus())) {
        LOG.info(String.format("Disable rule %s:%s", ruleDto.getRepositoryKey(), ruleDto.getRuleKey()));
        ruleDto.setStatus(Rule.STATUS_REMOVED);
        ruleDto.setUpdatedAt(buffer.now());
        for (RuleRuleTagDto removed : buffer.tagsByRuleId.removeAll(ruleDto.getId())) {
          ruleDao.deleteTag(removed, sqlSession);
        }
        ruleDao.update(ruleDto, sqlSession);
        removedRules.add(ruleDto);
        if (removedRules.size() % 100 == 0) {
          sqlSession.commit();
        }
      }
    }
    sqlSession.commit();

    return removedRules;
  }

  /**
   * SONAR-4642
   * <p/>
   * Remove active rules on repositories that still exists.
   * <p/>
   * For instance, if the javascript repository do not provide anymore some rules, active rules related to this rules will be removed.
   * But if the javascript repository do not exists anymore, then related active rules will not be removed.
   * <p/>
   * The side effect of this approach is that extended repositories will not be managed the same way.
   * If an extended repository do not exists anymore, then related active rules will be removed.
   */
  private void removeActiveRulesOnStillExistingRepositories(List<RuleDto> removedRules, RulesDefinition.Context context) {
    List<String> repositoryKeys = newArrayList(Iterables.transform(context.repositories(), new Function<RulesDefinition.Repository, String>() {
        @Override
        public String apply(RulesDefinition.Repository input) {
          return input.key();
        }
      }
    ));

    for (RuleDto rule : removedRules) {
      // SONAR-4642 Remove active rules only when repository still exists
      if (repositoryKeys.contains(rule.getRepositoryKey())) {
        profilesManager.removeActivatedRules(rule.getId());
      }
    }
  }

  private void index(Buffer buffer, DbSession sqlSession) {
    String[] ids = ruleRegistry.reindex(buffer.rulesById.values(), sqlSession);
    ruleRegistry.removeDeletedRules(ids);
    esRuleTags.putAllTags(buffer.referenceTagsByTagValue.values());
  }

  static class Buffer {
    private Date now;
    private List<Integer> unprocessedRuleIds = newArrayList();
    private Map<RuleKey, RuleDto> rulesByKey = Maps.newHashMap();
    private Map<Integer, RuleDto> rulesById = Maps.newHashMap();
    private Multimap<Integer, RuleParamDto> paramsByRuleId = ArrayListMultimap.create();
    private Multimap<Integer, RuleRuleTagDto> tagsByRuleId = ArrayListMultimap.create();
    private Map<String, RuleTagDto> referenceTagsByTagValue = Maps.newHashMap();
    private Map<Integer, CharacteristicDto> characteristicsById = Maps.newHashMap();

    Buffer(long now) {
      this.now = new Date(now);
    }

    Date now() {
      return now;
    }

    void add(RuleDto rule) {
      rulesById.put(rule.getId(), rule);
      rulesByKey.put(RuleKey.of(rule.getRepositoryKey(), rule.getRuleKey()), rule);
    }

    void add(CharacteristicDto characteristicDto) {
      characteristicsById.put(characteristicDto.getId(), characteristicDto);
    }

    void add(RuleTagDto tag) {
      referenceTagsByTagValue.put(tag.getTag(), tag);
    }

    void add(RuleParamDto param) {
      paramsByRuleId.put(param.getRuleId(), param);
    }

    void add(RuleRuleTagDto tag) {
      tagsByRuleId.put(tag.getRuleId(), tag);
    }

    void remove(RuleRuleTagDto tag) {
      tagsByRuleId.remove(tag.getRuleId(), tag);
    }

    @CheckForNull
    RuleDto rule(RuleKey key) {
      return rulesByKey.get(key);
    }

    Collection<RuleParamDto> paramsForRuleId(Integer ruleId) {
      return paramsByRuleId.get(ruleId);
    }

    Collection<RuleRuleTagDto> tagsForRuleId(Integer ruleId) {
      return tagsByRuleId.get(ruleId);
    }

    boolean referenceTagExists(String tagValue) {
      return referenceTagsByTagValue.containsKey(tagValue);
    }

    Long referenceTagIdForValue(String tagValue) {
      return referenceTagsByTagValue.get(tagValue).getId();
    }

    void markUnprocessed(RuleDto ruleDto) {
      unprocessedRuleIds.add(ruleDto.getId());
    }

    void markProcessed(RuleDto ruleDto) {
      unprocessedRuleIds.remove(ruleDto.getId());
    }

    CharacteristicDto characteristic(@Nullable String subCharacteristic, String repo, String ruleKey, @Nullable Integer overridingCharacteristicId) {
      // Rule is not linked to a default characteristic or characteristic has been disabled by user
      if (subCharacteristic == null) {
        return null;
      }
      CharacteristicDto characteristicDto = findCharacteristic(subCharacteristic);
      if (characteristicDto == null) {
        // Log a warning only if rule has not been overridden by user
        if (overridingCharacteristicId == null) {
          LOG.warn(String.format("Characteristic '%s' has not been found on rule '%s:%s'", subCharacteristic, repo, ruleKey));
        }
      } else if (characteristicDto.getParentId() == null) {
        throw MessageException.of(String.format("Rule '%s:%s' cannot be linked on the root characteristic '%s'", repo, ruleKey, subCharacteristic));
      }
      return characteristicDto;
    }

    @CheckForNull
    private CharacteristicDto findCharacteristic(final String key) {
      return Iterables.find(characteristicsById.values(), new Predicate<CharacteristicDto>() {
        @Override
        public boolean apply(@Nullable CharacteristicDto input) {
          return input != null && key.equals(input.getKey());
        }
      }, null);
    }
  }
}
