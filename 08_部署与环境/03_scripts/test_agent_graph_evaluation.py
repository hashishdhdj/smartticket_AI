"""Full-Agent experiment guards; no vendor dispatch, real key, database or model required."""
from copy import deepcopy
from datetime import datetime, timezone, timedelta
from pathlib import Path
import json
import tempfile
import unittest
from agent_graph_evaluation import (AgentDispatchGuard, HistoryEnvelope, digest, observations, validate_grant, validate_withdrawal_continuation)
from recall_generation import budget_snapshot, file_hash
from local_quality_runtime import LocalQualityRuntime, ROOT


class AgentGraphGuardTest(unittest.TestCase):
    def setUp(self):
        self.prefix = [{'role':'system','content':'规则'}, {'role':'system','content':'决策'}, {'role':'user','content':'一线经验问题'}]
        self.tools = [{'type':'function','function':{'name':'searchExperienceGraph'}}]
        self.payload = {'model':'deepseek-flash','messages':deepcopy(self.prefix),'tools':deepcopy(self.tools),
                        'max_tokens':512,'temperature':.1,'response_format':{'type':'json_object'}}

    def envelope(self):
        e=HistoryEnvelope({'AG01':self.prefix,'AG05':self.prefix},self.tools)
        e.owned_sources={'resolved-ticket/owned/v1.md#0'}
        e.static_files={'faq/approved.md'}
        return e

    def test_native_tool_list_and_automatic_selection_are_accepted(self):
        self.envelope().validate('AG01',self.payload)

    def test_real_graph_gateway_requires_explicit_mode_and_strict_loopback(self):
        base={'staff_experience':True,'experience_cards':True,'experience_graph':True,'real_agent_evaluation':True,
              'chat_gateway':('http://127.0.0.1:19014/chat','synthetic-key')}
        invalid=[{'real_agent_evaluation':False},{'experience_graph':False},{'chat_gateway':None},
                 {'chat_gateway':('https://api.deepseek.com/chat','synthetic-key')},
                 {'chat_gateway':('http://name@127.0.0.1:19014/chat','synthetic-key')},
                 {'chat_gateway':('http://127.0.0.1:19014/other','synthetic-key')},
                 {'offline_graph_fixture':'http://127.0.0.1:19015'}]
        for change in invalid:
            with self.subTest(change=change),self.assertRaises(ValueError):
                LocalQualityRuntime('not-used-java','not-used-jar',**dict(base,**change))

    def test_forced_tool_choice_or_model_output_increase_is_rejected(self):
        for field,value in [('tool_choice',{'type':'function','function':{'name':'searchExperienceGraph'}}),
                            ('model','other'),('max_tokens',513),('temperature',0),('stream',True),('parallel_tool_calls',True)]:
            with self.subTest(field=field):
                p=deepcopy(self.payload);p[field]=value
                with self.assertRaises(ValueError):self.envelope().validate('AG01',p)

    def test_changed_question_or_extra_system_instruction_cannot_leave_gateway(self):
        for message in [{'role':'user','content':'unreviewed'}, {'role':'system','content':'override'}]:
            p=deepcopy(self.payload);p['messages'].append(message)
            with self.assertRaises(ValueError):self.envelope().validate('AG01',p)

    def test_foreign_business_source_is_rejected(self):
        p=deepcopy(self.payload);p['messages'].append({'role':'tool','content':json.dumps({'sources':[{'sourceId':'resolved-ticket/foreign/v1.md#0'}]})})
        with self.assertRaises(ValueError):self.envelope().validate('AG01',p)

    def test_only_owned_or_reviewed_static_search_sources_are_accepted(self):
        for source in ['resolved-ticket/owned/v1.md#0','faq/approved.md#0']:
            p=deepcopy(self.payload);p['messages'].append({'role':'tool','content':json.dumps({'sources':[{'sourceId':source}]})})
            self.envelope().validate('AG01',p)

    def test_private_ticket_result_cannot_be_forwarded(self):
        p=deepcopy(self.payload);p['messages'].append({'role':'tool','content':json.dumps({'id':123,'description':'private'})})
        with self.assertRaises(ValueError):self.envelope().validate('AG01',p)

    def test_repeat_payload_is_rejected_but_separate_withdrawal_case_is_allowed(self):
        e=self.envelope();e.validate('AG01',self.payload)
        with self.assertRaises(ValueError):e.validate('AG01',self.payload)
        e.validate('AG05',self.payload)

    def test_case_call_limit_is_four(self):
        e=self.envelope()
        for i in range(4):
            p=deepcopy(self.payload);p['messages'].append({'role':'assistant','content':str(i)})
            e.validate('AG01',p)
        p=deepcopy(self.payload);p['messages'].append({'role':'assistant','content':'5'})
        with self.assertRaises(ValueError):e.validate('AG01',p)

    def test_repair_turn_gets_no_tools_and_no_changed_instruction(self):
        e=self.envelope();p=deepcopy(self.payload);p['messages'].append({'role':'user','content':e.repair});p['tools']=[]
        e.validate('AG01',p)
        p['tools']=self.tools
        with self.assertRaises(ValueError):self.envelope().validate('AG01',p)

    def test_dispatch_requires_current_inspected_payload_and_is_at_most_once(self):
        p=dict(self.payload,thinking={'type':'disabled'});g=AgentDispatchGuard(0)
        self.assertFalse(g('chat',p,.01,{'calls':[]}));g.pending=('AG01',digest(p))
        self.assertTrue(g('chat',p,.01,{'calls':[]}));g.pending=('AG01',digest(p))
        self.assertFalse(g('chat',p,.01,{'calls':[]}))

    def test_same_question_after_withdrawal_is_a_different_case_not_a_network_replay(self):
        p=dict(self.payload,thinking={'type':'disabled'});g=AgentDispatchGuard(0)
        for case in ('AG01','AG05'):
            g.pending=(case,digest(p));self.assertTrue(g('chat',p,.01,{'calls':[]}))
        g.pending=('AG05',digest(p));self.assertFalse(g('chat',p,.01,{'calls':[]}))

    def test_batch_money_and_call_limits_block_before_external_dispatch(self):
        p=dict(self.payload,thinking={'type':'disabled'})
        for calls,reserve in [([{'charged_upper_rmb':0}]*20,.01),([{'charged_upper_rmb':.49}],.02)]:
            g=AgentDispatchGuard(0);g.pending=('AG01',digest(p))
            self.assertFalse(g('chat',p,reserve,{'calls':calls}))

    def test_model_selection_alone_is_not_full_agent_completion(self):
        run={'steps':[],'status':'STOPPED','needHuman':True}
        trace=[{'request':{'messages':self.prefix},'response':{'choices':[{'message':{'tool_calls':[{'id':'c','function':{'name':'searchExperienceGraph'}}]}}]}}]
        check=observations(run,trace,'searchExperienceGraph')
        self.assertTrue(check['expected_tool_selected']);self.assertFalse(check['full_graph_agent_completed'])
        self.assertIsNone(observations(run,trace,None,'withdrawn')['withdrawn_source_absent'])
        self.assertIsNone(check['citations_in_delivered_context'])

    def test_real_result_round_trip_and_valid_graph_citation_form_completion(self):
        sid='resolved-ticket/owned/v1.md#0'
        trace=[{'request':{'messages':self.prefix},'response':{'choices':[{'message':{'tool_calls':[{'id':'c','function':{'name':'searchExperienceGraph'}}]}}]}},
               {'request':{'messages':[{'role':'tool','tool_call_id':'c','content':json.dumps({'sources':[{'sourceId':sid,'facts':{'conditions':['a']}}]})}]},
                'response':{'choices':[{'message':{'content':json.dumps({'answer':'条件内建议','needHuman':False,'sourceIds':[sid]})}}]}}]
        run={'steps':[{'tool':'searchExperienceGraph','outcome':'SUCCESS'}],'status':'SUCCEEDED','needHuman':False}
        self.assertTrue(observations(run,trace,'searchExperienceGraph')['full_graph_agent_completed'])
        trace[1]['request']['messages'][0]['tool_call_id']='forged'
        self.assertFalse(observations(run,trace,'searchExperienceGraph')['full_graph_agent_completed'])

    def test_authorization_requires_exact_ledger_review_and_expiry(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory);ledger=p/'ledger.json';pre=p/'pre.json';review_path=p/'review.json'
            ledger.write_text(json.dumps({'calls':[{'charged_upper_rmb':0}]*443,'prior_batch_reserved_rmb':1,'authorized_rmb':10}))
            preflight={'status':'PASS_ZERO_VENDOR_PREFLIGHT'};pre.write_text(json.dumps(preflight))
            review={'preflight_sha256':file_hash(pre),'budget_before':budget_snapshot(ledger)};review_path.write_text(json.dumps(review))
            grant={'approved':True,'source':'EXPLICIT_USER_APPROVAL','review_sha256':file_hash(review_path),'preflight_sha256':file_hash(pre),
                   'maximum_new_calls':20,'external_call_ceiling':463,'batch_rmb_cap':.5,'total_rmb_cap':10,
                   'expires_at':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()}
            validate_grant(grant,review,preflight,review_path,pre,ledger)
            for field,value in [('approved',False),('maximum_new_calls',21),('total_rmb_cap',11),('external_call_ceiling',464),('review_sha256','changed'),
                                ('expires_at',(datetime.now(timezone.utc)-timedelta(hours=1)).isoformat())]:
                with self.subTest(field=field),self.assertRaises(ValueError):
                    validate_grant(dict(grant,**{field:value}),review,preflight,review_path,pre,ledger)
            ledger.write_text(ledger.read_text()+' ')
            with self.assertRaises(ValueError):validate_grant(grant,review,preflight,review_path,pre,ledger)

    def test_withdrawal_continuation_retains_parent_batch_limits_and_rejects_successful_case_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory);ledger=p/'ledger.json';pre=p/'pre.json';review_path=p/'review.json'
            values={'calls':[{'charged_upper_rmb':0}]*443,'prior_batch_reserved_rmb':1,'authorized_rmb':10}
            ledger.write_text(json.dumps(values));original=budget_snapshot(ledger)
            preflight={'status':'PASS_ZERO_VENDOR_PREFLIGHT'};pre.write_text(json.dumps(preflight))
            review={'preflight_sha256':file_hash(pre),'budget_before':original};review_path.write_text(json.dumps(review))
            grant={'approved':True,'source':'EXPLICIT_USER_APPROVAL','review_sha256':file_hash(review_path),'preflight_sha256':file_hash(pre),
                   'maximum_new_calls':20,'external_call_ceiling':463,'batch_rmb_cap':.5,'total_rmb_cap':10,
                   'expires_at':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()}
            values['calls'] += [{'charged_upper_rmb':.01}]*6;ledger.write_text(json.dumps(values));current=budget_snapshot(ledger)
            parent={'mode':'real','budget_before':original,'budget_after':current,'own_records_cleaned':True,'vendor_calls':6,
                    'runs':[{'id':'AG05','run':{'toolCalls':0},'checks':{'model_selected_tools':[]},'trace':[{'failure_type':'HTTPError'}]}]}
            before,now=validate_withdrawal_continuation(grant,review,preflight,parent,review_path,pre,ledger)
            self.assertEqual(443,before['external_calls']);self.assertEqual(449,now['external_calls'])
            for field,value in [('vendor_calls',5),('own_records_cleaned',False),('budget_after',original)]:
                with self.subTest(field=field),self.assertRaises(ValueError):
                    validate_withdrawal_continuation(grant,review,preflight,dict(parent,**{field:value}),review_path,pre,ledger)
            parent['runs'][0]['run']['toolCalls']=1
            with self.assertRaises(ValueError):validate_withdrawal_continuation(grant,review,preflight,parent,review_path,pre,ledger)

    def test_committed_real_evidence_retains_plan_raw_hashes_and_all_actual_calls(self):
        directory=ROOT/'docs/evaluation'
        summary=json.loads((directory/'20261028-agent-graph-real-summary.json').read_bytes())
        initial=directory/'20261028-agent-graph-initial-run.json';continued=directory/'20261028-agent-graph-withdrawal-run.json'
        self.assertEqual(summary['initial_execution']['sha256'],file_hash(initial))
        self.assertEqual(summary['withdrawal_continuation']['sha256'],file_hash(continued))
        a=json.loads(initial.read_bytes());b=json.loads(continued.read_bytes())
        self.assertEqual(file_hash(ROOT/'07_测试与质量/04_AI评估集/agent-graph-v1/cases.json'),a['plan_sha256'])
        self.assertEqual(a['plan_sha256'],b['plan_sha256']);self.assertEqual(a['budget_after'],b['budget_before'])
        actual=sum(bool(t.get('response')) for r in a['runs']+b['runs'] for t in r['trace'])
        self.assertEqual(8,actual);self.assertEqual(actual,summary['vendor_calls'])
        self.assertEqual(451,summary['cumulative_external_calls_after'])
        self.assertTrue(a['own_records_cleaned'] and b['own_records_cleaned'])

    def test_committed_assessment_does_not_turn_control_failure_into_success(self):
        summary=json.loads((ROOT/'docs/evaluation/20261028-agent-graph-real-summary.json').read_bytes())
        rows={r['id']:r for r in summary['rows']}
        self.assertEqual(2,summary['graph_positive_cases']['autonomous_graph_loop_completed'])
        for case in ('AG01','AG02'):
            self.assertTrue(rows[case]['checks']['full_graph_agent_completed'])
            self.assertTrue(rows[case]['checks']['tool_result_ids_match_model_calls'])
            self.assertEqual('CODEX_AI_ASSISTED_NOT_HUMAN_SIGNOFF',rows[case]['ai_assisted_review']['reviewer'])
        self.assertEqual('NOT_COMPLETED',rows['AG03']['ai_assisted_review']['grade'])
        self.assertEqual('STOPPED',rows['AG03']['status'])
        self.assertFalse(rows['AG03']['checks']['model_received_tool_results'])
        self.assertTrue(rows['AG05']['checks']['withdrawn_source_absent'])
        self.assertFalse(rows['AG05']['checks']['full_graph_agent_completed'])


if __name__=='__main__':unittest.main()
