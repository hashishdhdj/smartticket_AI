"""Run the actual Java corpus loader, using the already-tested classpath, without Spring/DB/vendors."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[2]
service = root / '06_项目源代码/ticket-service'
temp = root / '.tmp'
jar = service / 'target/ticket-service-0.0.1-SNAPSHOT.jar'
with zipfile.ZipFile(jar) as archive:
    for source in (service / 'src/main/resources/knowledge').glob('*/*.md'):
        entry = 'BOOT-INF/classes/knowledge/' + source.parent.name + '/' + source.name
        assert archive.read(entry) == source.read_bytes(), 'JAR contains stale corpus: ' + source.name
report = service / 'target/surefire-reports/TEST-com.smartticket.rag.KnowledgeCorpusLoaderTest.xml'
properties = {p.attrib['name']: p.attrib['value'] for p in ET.parse(report).findall('properties/property')}
classpath = properties.get('surefire.test.class.path', properties['java.class.path'])
java_source = temp / 'ExportKnowledge.java'
java_source.write_text('''import com.smartticket.rag.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
public class ExportKnowledge {
    public static void main(String[] args) throws Exception {
        var chunks = new KnowledgeCorpusLoader(new KnowledgeChunker()).loadAll();
        new ObjectMapper().writeValue(Path.of(args[0]).toFile(), chunks);
        System.out.println("Exported actual Java chunks: " + chunks.size());
    }
}
''', encoding='utf-8')
java = Path(os.environ['JAVA_HOME']) / ('bin/java.exe' if os.name == 'nt' else 'bin/java')
output = temp / 'phase4-java-chunks.json'
subprocess.run([str(java), '-cp', classpath, str(java_source), str(output)], check=True, cwd=service, timeout=30)
chunks = json.loads(output.read_text(encoding='utf-8'))
snapshot_file = root / '07_测试与质量/06_阶段测试报告/20261014_知识库审查快照.json'
snapshot = json.loads(snapshot_file.read_text(encoding='utf-8'))
snapshot['java_chunk_count'] = len(chunks)
snapshot['java_chunks'] = [{'source_ref': f'{c["sourceFile"]}#{c["sectionTitle"]}', 'chunk_index': c['chunkIndex'],
                            'content_hash': hashlib.sha256(c['content'].encode()).hexdigest()} for c in chunks]
snapshot_file.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Sections and Java chunks tracked separately:', snapshot['section_count'], len(chunks))
