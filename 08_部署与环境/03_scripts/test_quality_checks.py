"""Check metric denominators and stop unverified artifacts/modified frozen data."""
from pathlib import Path
import hashlib
import json
import shutil
import tempfile
import unittest
import zipfile
from quality_checks import DATASET, ROOT, retrieval_row, summarize, verify_artifact, verify_dataset, validate_index


class QualityMetricTest(unittest.TestCase):
    def response(self, refs, confidence=.8, degraded=None):
        return {'matched': bool(refs), 'confidence': confidence, 'needHuman': True, 'statusReason': 'AI_UNAVAILABLE',
            'degradedSources': degraded or [], 'sources': [{'sourceFile': file, 'title': title} for file, title in refs]}

    def test_hit_recall_and_mrr_differ_for_multiple_expected_sources(self):
        row = retrieval_row({'id': 'a', 'expected_sources': ['a#one','b#two'], 'should_abstain': False},
                            self.response([('c','noise'),('a','one')]), .63)
        self.assertTrue(row['hit_at_3']);self.assertEqual(.5,row['recall_at_3']);self.assertEqual(.5,row['reciprocal_rank_at_3'])

    def test_disabled_chat_needhuman_is_not_threshold_rejection(self):
        row = retrieval_row({'id': 'n', 'expected_sources': [], 'should_abstain': True}, self.response([('a','one')]), .63)
        self.assertTrue(row['gate_allowed']);self.assertEqual(1,summarize([row])['negative_gate_pass_rate'])
        self.assertEqual(0,summarize([row])['generated_answer_count'])

    def test_negative_cases_do_not_inflate_positive_retrieval_denominator(self):
        pos = retrieval_row({'id':'p','expected_sources':['a#one'],'should_abstain':False},self.response([('a','one')]),.63)
        neg = retrieval_row({'id':'n','expected_sources':[],'should_abstain':True},self.response([('a','one')],.5),.63)
        result=summarize([pos,neg]);self.assertEqual(1,result['macro_recall_at_3']);self.assertEqual(0,result['negative_gate_pass_rate'])

    def test_degraded_result_is_visible_and_not_called_answer_accuracy(self):
        row=retrieval_row({'id':'p','expected_sources':['a#one'],'should_abstain':False},self.response([],0,['VECTOR']),.63)
        result=summarize([row]);self.assertEqual(1,result['degraded_count']);self.assertIsNone(result['semantic_answer_support'])

    def test_frozen_question_edit_fails_before_dispatch(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'set';shutil.copytree(DATASET,path)
            with (path/'retrieval.jsonl').open('a',encoding='utf-8') as output:output.write('{}\n')
            with self.assertRaises(ValueError):verify_dataset(path,ROOT)

    def index_rows(self):
        return [{'source_ref':'faq/test.md#section','chunk_index':index,'content':text,
            'hash':hashlib.sha256(text.encode()).hexdigest(),'model':'bge-m3:latest','dimensions':1024}
            for index,text in enumerate(('first chunk','second chunk'))]

    def test_multiple_chunks_per_section_are_valid_and_counted_separately(self):
        rows=self.index_rows();result=validate_index(rows,{'faq/test.md#section'})
        self.assertEqual(2,len(result));self.assertNotIn('content',result[0])

    def test_duplicate_physical_chunk_identity_is_rejected(self):
        rows=self.index_rows();rows[1]['chunk_index']=0
        with self.assertRaises(ValueError):validate_index(rows,{'faq/test.md#section'})

    def test_wrong_model_or_content_hash_is_rejected(self):
        for field,value in (('model','another-model'),('hash','0'*64)):
            rows=self.index_rows();rows[0][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):validate_index(rows,{'faq/test.md#section'})


class ArtifactGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.service=self.root/'06_项目源代码/ticket-service';(self.service/'src/main').mkdir(parents=True)
        self.source=self.service/'src/main/Test.java';self.source.write_text('class Test {}',encoding='utf-8')
        self.jar=self.root/'test.jar';self.rebuild()

    def rebuild(self, private=False):
        with zipfile.ZipFile(self.jar,'w') as z:
            z.writestr('BOOT-INF/classes/Test.class',b'synthetic class')
            if private:z.writestr('BOOT-INF/classes/application-local.yml',b'private')
        report=self.root/'docs/evaluation/20261019-dynamic-knowledge.json';report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps({'known_credentials_and_jar_audit':{
            'jar_sha256':hashlib.sha256(self.jar.read_bytes()).hexdigest(),
            'source_files':[{'path':'src/main/Test.java','sha256':hashlib.sha256(self.source.read_bytes()).hexdigest()}]}}),encoding='utf-8')

    def test_matching_artifact_and_source_pass(self):
        self.assertEqual(1,verify_artifact(self.jar,self.root)['source_files_checked'])

    def test_old_or_tampered_jar_is_rejected(self):
        with self.jar.open('ab') as output:output.write(b'changed')
        with self.assertRaises(ValueError):verify_artifact(self.jar,self.root)

    def test_added_or_modified_runtime_source_is_rejected(self):
        (self.service/'src/main/New.java').write_text('new',encoding='utf-8')
        with self.assertRaises(ValueError):verify_artifact(self.jar,self.root)
        self.source.write_text('changed',encoding='utf-8')
        with self.assertRaises(ValueError):verify_artifact(self.jar,self.root)

    def test_private_config_rejected_even_if_manifest_hash_matches(self):
        self.rebuild(private=True)
        with self.assertRaises(ValueError):verify_artifact(self.jar,self.root)

    def write_rebuild_record(self, failures=0):
        path=self.root/'docs/evaluation/20261019-dynamic-knowledge.json'
        baseline=json.loads(path.read_text(encoding='utf-8'))
        baseline['java_profiles']={key:{'totals':{'tests':1}} for key in ('unit','integration')}
        path.write_text(json.dumps(baseline),encoding='utf-8')
        with self.jar.open('ab') as output:output.write(b'new zip metadata')
        sources=baseline['known_credentials_and_jar_audit']['source_files']
        record={'status':'PASS','jar_sha256':hashlib.sha256(self.jar.read_bytes()).hexdigest(),'vendor_calls':0,
            'source_manifest_sha256':hashlib.sha256(json.dumps(sources,sort_keys=True).encode()).hexdigest(),
            'profiles':{name:{'tests':1,'failures':failures,'errors':0,'skipped':0} for name in ('unit','integration')}}
        p=self.root/'.tmp/quality-artifact-build.json';p.parent.mkdir();p.write_text(json.dumps(record),encoding='utf-8')

    def test_rebuilt_jar_requires_matching_full_profile_evidence(self):
        self.write_rebuild_record()
        self.assertTrue(verify_artifact(self.jar,self.root)['current_source_rebuilt_and_tested'])

    def test_failed_rebuild_cannot_authorize_a_new_jar(self):
        self.write_rebuild_record(failures=1)
        with self.assertRaises(ValueError):verify_artifact(self.jar,self.root)


if __name__ == '__main__':unittest.main()
