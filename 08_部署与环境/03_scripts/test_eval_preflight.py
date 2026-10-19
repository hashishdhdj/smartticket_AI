"""Offline checks that stale artifacts fail before an app or paid gateway can start."""
from pathlib import Path
from unittest import TestCase,main,mock
import contextlib,hashlib,io,json,runpy,sys,tempfile,zipfile
import phase4_eval_runtime as runtime
import threshold_policy

SCRIPT=Path(__file__).with_name('stage4-evaluate.py')

class EvaluationPreflightTests(TestCase):
    def setUp(self):
        (runtime.ROOT/'.tmp').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=runtime.ROOT/'.tmp')
        self.root=Path(self.temp.name)
        self.service=self.root/'06_项目源代码/ticket-service'
        self.prompt=self.service/'src/main/resources/prompts/example.md'
        self.prompt.parent.mkdir(parents=True);self.prompt.write_text('current prompt',encoding='utf-8')
        (self.service/'pom.xml').write_text('<project/>',encoding='utf-8')
        self.reports=self.root/'07_测试与质量/06_阶段测试报告';self.reports.mkdir(parents=True)
        self.dataset=self.root/'07_测试与质量/04_AI评估集/phase4-v3-newwording';self.dataset.mkdir(parents=True)
        data=b'{"id":"NW13","kind":"rag"}\n';(self.dataset/'cases.jsonl').write_bytes(data)
        self.write(self.dataset/'manifest.json',{'files':{'cases.jsonl':{'sha256':hashlib.sha256(data).hexdigest(),'count':1}}})
        self.write(self.reports/'20261014_新语料开发集阈值核查.json',{'status':'PASS_CALIBRATION_ONLY','embedding_model':'fixture-model'})
        files=[{'path':p.relative_to(self.service).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in [self.service/'pom.xml',self.prompt]]
        self.manifest={'files':files,'source_manifest_sha256':hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()}
        self.write(self.reports/'source.json',self.manifest)
        (self.root/'.tmp').mkdir()
        self.write(self.root/'.tmp/phase4-delivery-build.json',{'run_id':'fixture','source_manifest_path':'07_测试与质量/06_阶段测试报告/source.json','source_manifest_sha256':self.manifest['source_manifest_sha256']})
        self.jar=self.root/'app.jar';self.rebuild_jar('current prompt')

    def tearDown(self):self.temp.cleanup()

    def write(self,path,value):path.write_text(json.dumps(value),encoding='utf-8')

    def rebuild_jar(self,prompt):
        with zipfile.ZipFile(self.jar,'w') as archive:archive.writestr('BOOT-INF/classes/prompts/example.md',prompt)
        self.write(self.reports/'fixture_干净交付包构建与启动.json',{'status':'PASS','delivery_artifact_path':'app.jar','jar_sha256':hashlib.sha256(self.jar.read_bytes()).hexdigest(),'source_manifest_sha256':self.manifest['source_manifest_sha256']})

    def execute(self):
        with mock.patch.object(runtime,'ROOT',self.root),mock.patch.object(runtime,'embedding_provider',return_value={'model':'fixture-model'}),\
             mock.patch.object(runtime,'BudgetProxy',side_effect=AssertionError('Gateway must not start')) as proxy,\
             mock.patch.object(runtime,'EvalRuntime',side_effect=AssertionError('App must not start')) as app,\
             mock.patch.object(threshold_policy,'active_threshold',return_value=.63),\
             mock.patch.object(sys,'argv',[str(SCRIPT),'--dataset','phase4-v3-newwording','--kind','rag','--case-ids','NW13','--preflight-only']),\
             contextlib.redirect_stdout(io.StringIO()) as output:
            try:runpy.run_path(str(SCRIPT),run_name='__main__')
            finally:proxy.assert_not_called();app.assert_not_called()

    def test_matching_delivery_exits_without_gateway_or_application(self):
        with self.assertRaises(SystemExit) as raised:self.execute()
        self.assertEqual(0,raised.exception.code)

    def test_modified_source_is_rejected(self):
        (self.service/'pom.xml').write_text('changed',encoding='utf-8')
        with self.assertRaisesRegex(AssertionError,'Source changed'):self.execute()

    def test_modified_jar_is_rejected(self):
        with self.jar.open('ab') as stream:stream.write(b'changed')
        with self.assertRaisesRegex(AssertionError,'JAR changed'):self.execute()

    def test_matching_jar_hash_with_stale_prompt_is_rejected(self):
        self.rebuild_jar('old prompt')
        with self.assertRaisesRegex(AssertionError,'Stale delivery prompt'):self.execute()

    def test_changed_source_manifest_is_rejected(self):
        self.manifest['files'].pop()
        self.write(self.reports/'source.json',self.manifest)
        with self.assertRaisesRegex(AssertionError,'manifest changed'):self.execute()

if __name__=='__main__':main()
