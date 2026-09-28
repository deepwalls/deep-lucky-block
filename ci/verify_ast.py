"""AST sanity checks for surgical Java edits (local, no JDK).

Verifies, for each touched file:
1. The file parses with zero ERROR nodes (tree-sitter-java).
2. Every method/field signature present BEFORE the edit still exists AFTER
   (compared against the reference copy from origin/main).
3. Brace balance of the whole file.

These checks prove neither compilation, nor in-game behavior, nor timings.
"""
import subprocess
import sys

sys.path.insert(0, '/home/user/.cache/dlb-java-parser')
from tree_sitter import Parser, Language
import tree_sitter_java

BASE = '/home/user/deep-lucky-block/mod_project/src/main/java/deepluckyblock'
FILES = [
    'procedures/StructureTerrainPrep.java',
    'procedures/Structures5Procedure.java',
]

parser = Parser(Language(tree_sitter_java.language()))


def parse(text):
    return parser.parse(text.encode())


def signatures(root, text):
    """Collect method/field/constructor signature-ish identifiers, in order."""
    sigs = []
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type in ('method_declaration', 'constructor_declaration'):
            name, params, mods = None, None, ''
            for child in node.children:
                if child.type == 'identifier' and name is None:
                    name = child.text.decode()
                elif child.type == 'formal_parameters':
                    params = child.text.decode()
                elif child.type == 'modifiers':
                    mods = child.text.decode()
            # arity keeps builtins like above() distinct enough for presence checks
            if name:
                arity = params.count(',') + (1 if params.strip(' ()') else 0)
                sigs.append(f'{mods}|{name}/{arity}')
        elif node.type == 'field_declaration':
            decl = None
            mods = ''
            for child in node.children:
                if child.type == 'modifiers':
                    mods = child.text.decode()
                elif child.type == 'variable_declarator':
                    for sub in child.children:
                        if sub.type == 'identifier':
                            sigs.append(f'{mods}|field:{sub.text.decode()}')
                            break
        stack.extend(node.children)
    return sigs


def count_errors(root):
    errors = 0
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type == 'ERROR' or node.is_missing:
            errors += 1
        stack.extend(node.children)
    return errors


def ref_blob(path):
    return subprocess.run(
        ['git', '-C', '/home/user/deep-lucky-block', 'show', f'origin/main:{path}'],
        check=True, capture_output=True, text=True).stdout


bad = 0
for rel in FILES:
    full = f'{BASE}/{rel}'
    after = open(full, encoding='utf-8').read()
    before = ref_blob(f'mod_project/src/main/java/deepluckyblock/{rel}')
    tree = parse(after)
    errors = count_errors(tree.root_node)
    old_sigs = signatures(parse(before).root_node, before)
    new_sigs = signatures(tree.root_node, after)
    missing = [s for s in old_sigs if s not in new_sigs]
    balance = after.count('{') - after.count('}')
    print(f'{rel}: lines={len(after.splitlines())} parse_errors={errors} '
          f'signatures_lost={len(missing)} brace_balance={balance}')
    if missing:
        bad = 1
        for s in missing[:20]:
            print(f'  LOST: {s}')
    if errors or balance:
        bad = 1
        print('  !! parse errors or unbalanced braces')
raise SystemExit(bad)
