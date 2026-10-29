"""Add Agent reception tables to local development MySQL; no model calls or historic enrollment."""
from pathlib import Path
import argparse
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / '08_部署与环境/01_docker/.env')
    args = parser.parse_args()
    values = {}
    for line in args.env_file.read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('"\'')
    password = values.get('MYSQL_ROOT_PASSWORD')
    if not password:
        raise ValueError('Missing local MySQL credential; no credentials printed')
    env = os.environ.copy()
    env['MYSQL_PWD'] = password

    def sql(statement):
        result = subprocess.run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', 'smartticket-mysql',
            'mysql', '-uroot', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', 'smartticket'],
            input=statement.encode('utf-8'), capture_output=True, env=env, timeout=60)
        if result.returncode:
            raise RuntimeError('Reception migration/check failed; no credentials printed')
        return result.stdout.decode('utf-8').strip()

    counts = 'SELECT (SELECT COUNT(*) FROM ticket),(SELECT COUNT(*) FROM ticket_log);'
    before = sql(counts)
    sql((ROOT / '06_项目源代码/sql/09_agent_reception.sql').read_text(encoding='utf-8'))
    sql('SELECT ticket_id,state,input_version,handoff_summary FROM ticket_reception LIMIT 0;'
        'SELECT ticket_id,request_key,request_hash,content FROM ticket_reception_message LIMIT 0;')
    if sql(counts) != before:
        raise RuntimeError('Business counts changed during migration; investigate before activation')
    print('Agent reception schema ready. Existing tickets/logs retained; no historic enrollment, no model calls.')


if __name__ == '__main__':
    main()
