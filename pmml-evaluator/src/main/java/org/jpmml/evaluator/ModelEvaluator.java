/*
 * Copyright (c) 2016 Villu Ruusmann
 *
 * This file is part of JPMML-Evaluator
 *
 * JPMML-Evaluator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Evaluator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Evaluator.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.evaluator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.Table;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.DefineFunction;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.Field;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.InlineTable;
import org.dmg.pmml.LocalTransformations;
import org.dmg.pmml.MathContext;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.Model;
import org.dmg.pmml.ModelVerification;
import org.dmg.pmml.OpType;
import org.dmg.pmml.Output;
import org.dmg.pmml.PMML;
import org.dmg.pmml.ResultFeature;
import org.dmg.pmml.Target;
import org.dmg.pmml.Targets;
import org.dmg.pmml.TransformationDictionary;
import org.dmg.pmml.VerificationField;
import org.dmg.pmml.VerificationFields;

abstract
public class ModelEvaluator<M extends Model> implements Evaluator, Serializable {

	private PMML pmml = null;

	private M model = null;

	private ModelEvaluatorFactory modelEvaluatorFactory = null;

	private ValueFactoryFactory valueFactoryFactory = null;

	private ValueFactory<?> valueFactory = null;

	private Map<FieldName, DataField> dataFields = Collections.emptyMap();

	private Map<FieldName, DerivedField> derivedFields = Collections.emptyMap();

	private Map<String, DefineFunction> defineFunctions = Collections.emptyMap();

	private Map<FieldName, MiningField> miningFields = Collections.emptyMap();

	private Map<FieldName, DerivedField> localDerivedFields = Collections.emptyMap();

	private Map<FieldName, Target> targets = Collections.emptyMap();

	private Map<FieldName, org.dmg.pmml.OutputField> outputFields = Collections.emptyMap();

	transient
	private List<InputField> inputFields = null;

	transient
	private List<InputField> activeInputFields = null;

	transient
	private List<TargetField> targetResultFields = null;

	transient
	private List<OutputField> outputResultFields = null;


	protected ModelEvaluator(PMML pmml, M model){
		setPMML(Objects.requireNonNull(pmml));
		setModel(Objects.requireNonNull(model));

		DataDictionary dataDictionary = pmml.getDataDictionary();
		if(dataDictionary == null){
			throw new MissingElementException(pmml, PMMLElements.PMML_DATADICTIONARY);
		} // End if

		if(dataDictionary.hasDataFields()){
			this.dataFields = CacheUtil.getValue(dataDictionary, ModelEvaluator.dataFieldCache);
		}

		TransformationDictionary transformationDictionary = pmml.getTransformationDictionary();
		if(transformationDictionary != null && transformationDictionary.hasDerivedFields()){
			this.derivedFields = CacheUtil.getValue(transformationDictionary, ModelEvaluator.derivedFieldCache);
		} // End if

		if(transformationDictionary != null && transformationDictionary.hasDefineFunctions()){
			this.defineFunctions = CacheUtil.getValue(transformationDictionary, ModelEvaluator.defineFunctionCache);
		}

		MiningFunction miningFunction = model.getMiningFunction();
		if(miningFunction == null){
			throw new MissingAttributeException(MissingAttributeException.formatMessage(XPathUtil.formatElement(model.getClass()) + "@miningFunction"), model);
		}

		MiningSchema miningSchema = model.getMiningSchema();
		if(miningSchema == null){
			throw new MissingElementException(MissingElementException.formatMessage(XPathUtil.formatElement(model.getClass()) + "/" + XPathUtil.formatElement(MiningSchema.class)), model);
		} // End if

		if(miningSchema.hasMiningFields()){
			this.miningFields = CacheUtil.getValue(miningSchema, ModelEvaluator.miningFieldCache);
		}

		LocalTransformations localTransformations = model.getLocalTransformations();
		if(localTransformations != null && localTransformations.hasDerivedFields()){
			this.localDerivedFields = CacheUtil.getValue(localTransformations, ModelEvaluator.localDerivedFieldCache);
		}

		Targets targets = model.getTargets();
		if(targets != null && targets.hasTargets()){
			this.targets = CacheUtil.getValue(targets, ModelEvaluator.targetCache);
		}

		Output output = model.getOutput();
		if(output != null && output.hasOutputFields()){
			this.outputFields = CacheUtil.getValue(output, ModelEvaluator.outputFieldCache);
		}
	}

	abstract
	public Map<FieldName, ?> evaluate(ModelEvaluationContext context);

	/**
	 * <p>
	 * Configures the runtime behaviour of this Evaluator instance.
	 * </p>
	 *
	 * <p>
	 * Must be called once before the first evaluation.
	 * May be called any number of times between subsequent evaluations.
	 * </p>
	 */
	public void configure(ModelEvaluatorFactory modelEvaluatorFactory){
		setModelEvaluatorFactory(modelEvaluatorFactory);

		setValueFactoryFactory(null);
		setValueFactory(null);
	}

	@Override
	public MiningFunction getMiningFunction(){
		M model = getModel();

		return model.getMiningFunction();
	}

	public MathContext getMathContext(){
		M model = getModel();

		return model.getMathContext();
	}

	public DataField getDataField(FieldName name){

		if(Objects.equals(Evaluator.DEFAULT_TARGET_NAME, name)){
			return getDataField();
		}

		return this.dataFields.get(name);
	}

	/**
	 * @return A synthetic {@link DataField} element describing the default target field.
	 */
	protected DataField getDataField(){
		MiningFunction miningFunction = getMiningFunction();

		switch(miningFunction){
			case REGRESSION:
				MathContext mathContext = getMathContext();

				switch(mathContext){
					case FLOAT:
						return ModelEvaluator.DEFAULT_TARGET_CONTINUOUS_FLOAT;
					default:
						return ModelEvaluator.DEFAULT_TARGET_CONTINUOUS_DOUBLE;
				}
			case CLASSIFICATION:
			case CLUSTERING:
				return ModelEvaluator.DEFAULT_TARGET_CATEGORICAL_STRING;
			default:
				return null;
		}
	}

	public DerivedField getDerivedField(FieldName name){
		return this.derivedFields.get(name);
	}

	public DefineFunction getDefineFunction(String name){
		return this.defineFunctions.get(name);
	}

	public MiningField getMiningField(FieldName name){

		if(Objects.equals(Evaluator.DEFAULT_TARGET_NAME, name)){
			return null;
		}

		return this.miningFields.get(name);
	}

	public DerivedField getLocalDerivedField(FieldName name){
		return this.localDerivedFields.get(name);
	}

	public Target getTarget(FieldName name){
		return this.targets.get(name);
	}

	public org.dmg.pmml.OutputField getOutputField(FieldName name){
		return this.outputFields.get(name);
	}

	public boolean isPrimitive(){
		return this.localDerivedFields.isEmpty() && this.outputFields.isEmpty();
	}

	@Override
	public List<InputField> getInputFields(){

		if(this.inputFields == null){
			this.inputFields = createInputFields();
		}

		return this.inputFields;
	}

	InputField findInputField(FieldName name){
		return findModelField(getInputFields(), name);
	}

	@Override
	public List<InputField> getActiveFields(){

		if(this.activeInputFields == null){
			this.activeInputFields = createInputFields(MiningField.UsageType.ACTIVE);
		}

		return this.activeInputFields;
	}

	@Override
	public List<TargetField> getTargetFields(){

		if(this.targetResultFields == null){
			this.targetResultFields = createTargetFields();
		}

		return this.targetResultFields;
	}

	public TargetField getTargetField(){
		List<TargetField> targetFields = getTargetFields();

		if(targetFields.size() != 1){
			throw createMiningSchemaException("Expected 1 target field, got " + targetFields.size() + " target fields");
		}

		TargetField targetField = targetFields.get(0);

		return targetField;
	}

	TargetField findTargetField(FieldName name){
		return findModelField(getTargetFields(), name);
	}

	public FieldName getTargetFieldName(){
		TargetField targetField = getTargetField();

		return targetField.getName();
	}

	@Override
	public List<OutputField> getOutputFields(){

		if(this.outputResultFields == null){
			this.outputResultFields = createOutputFields();
		}

		return this.outputResultFields;
	}

	OutputField findOutputField(FieldName name){
		return findModelField(getOutputFields(), name);
	}

	protected EvaluationException createMiningSchemaException(String message){
		M model = getModel();

		MiningSchema miningSchema = model.getMiningSchema();

		return new EvaluationException(message, miningSchema);
	}

	@Override
	public void verify(){
		M model = getModel();

		ModelVerification modelVerification = model.getModelVerification();
		if(modelVerification == null){
			return;
		}

		VerificationBatch batch = CacheUtil.getValue(modelVerification, ModelEvaluator.batchCache);

		List<? extends Map<FieldName, ?>> records = batch.getRecords();

		List<InputField> inputFields = getInputFields();

		if(this instanceof HasGroupFields){
			HasGroupFields hasGroupFields = (HasGroupFields)this;

			records = EvaluatorUtil.groupRows(hasGroupFields, records);
		}

		List<TargetField> targetFields = getTargetFields();
		List<OutputField> outputFields = getOutputFields();

		SetView<FieldName> intersection = Sets.intersection(batch.keySet(), new LinkedHashSet<>(EvaluatorUtil.getNames(outputFields)));

		for(Map<FieldName, ?> record : records){
			Map<FieldName, Object> arguments = new LinkedHashMap<>();

			for(InputField inputField : inputFields){
				FieldName name = inputField.getName();

				FieldValue value = inputField.prepare(record.get(name));

				arguments.put(name, value);
			}

			Map<FieldName, ?> result = evaluate(arguments);

			// "If there exist VerificationField elements that refer to OutputField elements,
			// then any VerificationField element that refers to a MiningField element whose "usageType=target" should be ignored,
			// because they are considered to represent a dependent variable from the training data set, not an expected output"
			if(intersection.size() > 0){

				for(OutputField outputField : outputFields){
					FieldName name = outputField.getName();

					VerificationField verificationField = batch.get(name);
					if(verificationField == null){
						continue;
					}

					verify(record.get(name), result.get(name), verificationField.getPrecision(), verificationField.getZeroThreshold());
				}
			} else

			// "If there are no such VerificationField elements,
			// then any VerificationField element that refers to a MiningField element whose "usageType=target" should be considered to represent an expected output"
			{
				for(TargetField targetField : targetFields){
					FieldName name = targetField.getName();

					VerificationField verificationField = batch.get(name);
					if(verificationField == null){
						continue;
					}

					verify(record.get(name), EvaluatorUtil.decode(result.get(name)), verificationField.getPrecision(), verificationField.getZeroThreshold());
				}
			}
		}
	}

	private void verify(Object expected, Object actual, double precision, double zeroThreshold){

		if(expected == null){
			return;
		} // End if

		if(!(actual instanceof Collection)){
			DataType dataType = TypeUtil.getDataType(actual);

			expected = TypeUtil.parseOrCast(dataType, expected);
		}

		boolean acceptable = VerificationUtil.acceptable(expected, actual, precision, zeroThreshold);
		if(!acceptable){
			throw new EvaluationException("Values " + PMMLException.formatValue(expected) + " and " + PMMLException.formatValue(actual) + " do not match");
		}
	}

	@Override
	public Map<FieldName, ?> evaluate(Map<FieldName, ?> arguments){
		ModelEvaluationContext context = new ModelEvaluationContext(this);
		context.setArguments(arguments);

		return evaluate(context);
	}

	protected Field<?> resolveField(FieldName name){
		Field<?> result = getDataField(name);

		if(result == null){
			result = resolveDerivedField(name);
		}

		return result;
	}

	protected DerivedField resolveDerivedField(FieldName name){
		DerivedField result = getDerivedField(name);

		if(result == null){
			result = getLocalDerivedField(name);
		}

		return result;
	}

	protected List<InputField> createInputFields(){
		List<InputField> inputFields = getActiveFields();

		List<OutputField> outputFields = getOutputFields();
		if(outputFields.size() > 0){
			List<ResidualInputField> residualInputFields = null;

			for(OutputField outputField : outputFields){
				org.dmg.pmml.OutputField pmmlOutputField = outputField.getOutputField();

				if(!(pmmlOutputField.getResultFeature()).equals(ResultFeature.RESIDUAL)){
					continue;
				}

				int depth = outputField.getDepth();
				if(depth > 0){
					throw new UnsupportedElementException(pmmlOutputField);
				}

				FieldName targetFieldName = pmmlOutputField.getTargetField();
				if(targetFieldName == null){
					targetFieldName = getTargetFieldName();
				}

				DataField dataField = getDataField(targetFieldName);
				if(dataField == null){
					throw new MissingFieldException(targetFieldName, pmmlOutputField);
				}

				MiningField miningField = getMiningField(targetFieldName);
				if(miningField == null){
					throw new InvisibleFieldException(targetFieldName, pmmlOutputField);
				}

				ResidualInputField residualInputField = new ResidualInputField(dataField, miningField);

				if(residualInputFields == null){
					residualInputFields = new ArrayList<>();
				}

				residualInputFields.add(residualInputField);
			}

			if(residualInputFields != null && residualInputFields.size() > 0){
				inputFields = ImmutableList.copyOf(Iterables.concat(inputFields, residualInputFields));
			}
		}

		return inputFields;
	}

	protected List<InputField> createInputFields(MiningField.UsageType usageType){
		M model = getModel();

		MiningSchema miningSchema = model.getMiningSchema();

		List<InputField> inputFields = new ArrayList<>();

		if(miningSchema.hasMiningFields()){
			List<MiningField> miningFields = miningSchema.getMiningFields();

			for(MiningField miningField : miningFields){
				FieldName name = miningField.getName();

				if(!(miningField.getUsageType()).equals(usageType)){
					continue;
				}

				Field<?> field = getDataField(name);
				if(field == null){
					field = new VariableField(name);
				}

				InputField inputField = new InputField(field, miningField);

				inputFields.add(inputField);
			}
		}

		return ImmutableList.copyOf(inputFields);
	}

	protected List<TargetField> createTargetFields(){
		M model = getModel();

		MiningSchema miningSchema = model.getMiningSchema();

		List<TargetField> targetFields = new ArrayList<>();

		if(miningSchema.hasMiningFields()){
			List<MiningField> miningFields = miningSchema.getMiningFields();

			for(MiningField miningField : miningFields){
				FieldName name = miningField.getName();

				MiningField.UsageType usageType = miningField.getUsageType();
				switch(usageType){
					case TARGET:
					case PREDICTED:
						break;
					default:
						continue;
				}

				DataField dataField = getDataField(name);
				if(dataField == null){
					throw new MissingFieldException(name, miningField);
				}

				Target target = getTarget(name);

				TargetField targetField = new TargetField(dataField, miningField, target);

				targetFields.add(targetField);
			}
		}

		synthesis:
		if(targetFields.isEmpty()){
			DataField dataField = getDataField();

			if(dataField == null){
				break synthesis;
			}

			Target target = getTarget(dataField.getName());

			TargetField targetField = new TargetField(dataField, null, target);

			targetFields.add(targetField);
		}

		return ImmutableList.copyOf(targetFields);
	}

	protected List<OutputField> createOutputFields(){
		M model = getModel();

		Output output = model.getOutput();

		List<OutputField> resultFields = new ArrayList<>();

		if(output != null && output.hasOutputFields()){
			List<org.dmg.pmml.OutputField> outputFields = output.getOutputFields();

			for(org.dmg.pmml.OutputField outputField : outputFields){
				OutputField resultField = new OutputField(outputField);

				resultFields.add(resultField);
			}
		}

		return ImmutableList.copyOf(resultFields);
	}

	protected M ensureScorableModel(){
		M model = getModel();

		if(!model.isScorable()){
			throw new EvaluationException("Model is not scorable", model);
		}

		return model;
	}

	public <V> V getValue(LoadingCache<M, V> cache){
		M model = getModel();

		return CacheUtil.getValue(model, cache);
	}

	public <V> V getValue(Cache<M, V> cache, Callable<? extends V> loader){
		M model = getModel();

		return CacheUtil.getValue(model, cache, loader);
	}

	protected ModelEvaluatorFactory ensureModelEvaluatorFactory(){
		ModelEvaluatorFactory modelEvaluatorFactory = getModelEvaluatorFactory();

		if(modelEvaluatorFactory == null){
			modelEvaluatorFactory = ModelEvaluatorFactory.newInstance();

			setModelEvaluatorFactory(modelEvaluatorFactory);
		}

		return modelEvaluatorFactory;
	}

	protected ValueFactoryFactory ensureValueFactoryFactory(){
		ValueFactoryFactory valueFactoryFactory = getValueFactoryFactory();

		if(valueFactoryFactory == null){
			ModelEvaluatorFactory modelEvaluatorFactory = ensureModelEvaluatorFactory();

			valueFactoryFactory = modelEvaluatorFactory.getValueFactoryFactory();
			if(valueFactoryFactory == null){
				valueFactoryFactory = ValueFactoryFactory.newInstance();
			}

			setValueFactoryFactory(valueFactoryFactory);
		}

		return valueFactoryFactory;
	}

	protected ValueFactory<?> ensureValueFactory(){
		ValueFactory<?> valueFactory = getValueFactory();

		if(valueFactory == null){
			ValueFactoryFactory valueFactoryFactory = ensureValueFactoryFactory();

			MathContext mathContext = getMathContext();

			valueFactory = valueFactoryFactory.newValueFactory(mathContext);

			setValueFactory(valueFactory);
		}

		return valueFactory;
	}

	public PMML getPMML(){
		return this.pmml;
	}

	private void setPMML(PMML pmml){
		this.pmml = pmml;
	}

	public M getModel(){
		return this.model;
	}

	private void setModel(M model){
		this.model = model;
	}

	public ModelEvaluatorFactory getModelEvaluatorFactory(){
		return this.modelEvaluatorFactory;
	}

	private void setModelEvaluatorFactory(ModelEvaluatorFactory modelEvaluatorFactory){
		this.modelEvaluatorFactory = modelEvaluatorFactory;
	}

	public ValueFactoryFactory getValueFactoryFactory(){
		return this.valueFactoryFactory;
	}

	private void setValueFactoryFactory(ValueFactoryFactory valueFactoryFactory){
		this.valueFactoryFactory = valueFactoryFactory;
	}

	public ValueFactory<?> getValueFactory(){
		return this.valueFactory;
	}

	private void setValueFactory(ValueFactory<?> valueFactory){
		this.valueFactory = valueFactory;
	}

	static
	protected <M extends Model> M selectModel(PMML pmml, Class<? extends M> clazz){

		if(!pmml.hasModels()){
			throw new MissingElementException(MissingElementException.formatMessage(XPathUtil.formatElement(pmml.getClass()) + "/" + XPathUtil.formatElement(clazz)), pmml);
		}

		List<Model> models = pmml.getModels();

		Iterable<? extends M> filteredModels = Iterables.filter(models, clazz);

		M model = Iterables.getFirst(filteredModels, null);
		if(model == null){
			throw new MissingElementException(MissingElementException.formatMessage(XPathUtil.formatElement(pmml.getClass()) + "/" + XPathUtil.formatElement(clazz)), pmml);
		}

		return model;
	}

	static
	private <F extends ModelField> F findModelField(Collection<? extends F> fields, FieldName name){

		for(F field : fields){

			if(Objects.equals(field.getName(), name)){
				return field;
			}
		}

		return null;
	}

	static
	private VerificationBatch parseModelVerification(ModelVerification modelVerification){
		VerificationBatch result = new VerificationBatch();

		VerificationFields verificationFields = modelVerification.getVerificationFields();
		if(verificationFields == null){
			throw new MissingElementException(modelVerification, PMMLElements.MODELVERIFICATION_VERIFICATIONFIELDS);
		}

		for(VerificationField verificationField : verificationFields){
			result.put(verificationField.getField(), verificationField);
		}

		InlineTable inlineTable = modelVerification.getInlineTable();
		if(inlineTable == null){
			throw new MissingElementException(modelVerification, PMMLElements.MODELVERIFICATION_INLINETABLE);
		}

		Table<Integer, String, String> table = InlineTableUtil.getContent(inlineTable);

		List<Map<FieldName, Object>> records = new ArrayList<>();

		Set<Integer> rowKeys = table.rowKeySet();
		for(Integer rowKey : rowKeys){
			Map<String, String> row = table.row(rowKey);

			Map<FieldName, Object> record = new LinkedHashMap<>();

			for(VerificationField verificationField : verificationFields){
				FieldName name = verificationField.getField();
				String column = verificationField.getColumn();

				if(column == null){
					column = name.getValue();
				} // End if

				if(!row.containsKey(column)){
					continue;
				}

				record.put(name, row.get(column));
			}

			records.add(record);
		}

		Integer recordCount = modelVerification.getRecordCount();
		if(recordCount != null && recordCount != records.size()){
			throw new InvalidElementException(modelVerification);
		}

		result.setRecords(records);

		return result;
	}

	private static final DataField DEFAULT_TARGET_CONTINUOUS_FLOAT = new DataField(Evaluator.DEFAULT_TARGET_NAME, OpType.CONTINUOUS, DataType.FLOAT);
	private static final DataField DEFAULT_TARGET_CONTINUOUS_DOUBLE = new DataField(Evaluator.DEFAULT_TARGET_NAME, OpType.CONTINUOUS, DataType.DOUBLE);
	private static final DataField DEFAULT_TARGET_CATEGORICAL_STRING = new DataField(Evaluator.DEFAULT_TARGET_NAME, OpType.CATEGORICAL, DataType.STRING);

	private static final LoadingCache<DataDictionary, Map<FieldName, DataField>> dataFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<DataDictionary, Map<FieldName, DataField>>(){

		@Override
		public Map<FieldName, DataField> load(DataDictionary dataDictionary){
			return IndexableUtil.buildMap(dataDictionary.getDataFields());
		}
	});

	private static final LoadingCache<TransformationDictionary, Map<FieldName, DerivedField>> derivedFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<TransformationDictionary, Map<FieldName, DerivedField>>(){

		@Override
		public Map<FieldName, DerivedField> load(TransformationDictionary transformationDictionary){
			return IndexableUtil.buildMap(transformationDictionary.getDerivedFields());
		}
	});

	private static final LoadingCache<TransformationDictionary, Map<String, DefineFunction>> defineFunctionCache = CacheUtil.buildLoadingCache(new CacheLoader<TransformationDictionary, Map<String, DefineFunction>>(){

		@Override
		public Map<String, DefineFunction> load(TransformationDictionary transformationDictionary){
			return IndexableUtil.buildMap(transformationDictionary.getDefineFunctions());
		}
	});

	private static final LoadingCache<MiningSchema, Map<FieldName, MiningField>> miningFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<MiningSchema, Map<FieldName, MiningField>>(){

		@Override
		public Map<FieldName, MiningField> load(MiningSchema miningSchema){
			return IndexableUtil.buildMap(miningSchema.getMiningFields());
		}
	});

	private static final LoadingCache<LocalTransformations, Map<FieldName, DerivedField>> localDerivedFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<LocalTransformations, Map<FieldName, DerivedField>>(){

		@Override
		public Map<FieldName, DerivedField> load(LocalTransformations localTransformations){
			return IndexableUtil.buildMap(localTransformations.getDerivedFields());
		}
	});

	private static final LoadingCache<Targets, Map<FieldName, Target>> targetCache = CacheUtil.buildLoadingCache(new CacheLoader<Targets, Map<FieldName, Target>>(){

		@Override
		public Map<FieldName, Target> load(Targets targets){
			return IndexableUtil.buildMap(targets.getTargets(), true);
		}
	});

	private static final LoadingCache<Output, Map<FieldName, org.dmg.pmml.OutputField>> outputFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<Output, Map<FieldName, org.dmg.pmml.OutputField>>(){

		@Override
		public Map<FieldName, org.dmg.pmml.OutputField> load(Output output){
			return IndexableUtil.buildMap(output.getOutputFields());
		}
	});

	static
	private class VerificationBatch extends LinkedHashMap<FieldName, VerificationField> {

		private List<Map<FieldName, Object>> records = null;


		public List<Map<FieldName, Object>> getRecords(){
			return this.records;
		}

		private void setRecords(List<Map<FieldName, Object>> records){
			this.records = records;
		}
	}

	private static final LoadingCache<ModelVerification, VerificationBatch> batchCache = CacheUtil.buildLoadingCache(new CacheLoader<ModelVerification, VerificationBatch>(){

		@Override
		public VerificationBatch load(ModelVerification modelVerification){
			return parseModelVerification(modelVerification);
		}
	});
}
