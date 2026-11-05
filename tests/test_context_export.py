"""Run with python3 -m unittest discover -s tests. Uses synthetic sessions only."""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class ContextExportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.sessions = root / "codex" / "sessions"
        self.sessions.mkdir(parents=True)
        loader = importlib.machinery.SourceFileLoader(
            "context_export", str(Path(__file__).resolve().parents[1] / "tools/pocketagent-export-context"))
        self.mod = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
        with patch.dict(os.environ, {
            "POCKETAGENT_CODEX_HOME": str(root / "codex"),
            "POCKETAGENT_FROM_PHONE_DIR": str(root / "phone"),
            "POCKETAGENT_TRASH_DIR": str(root / "trash"),
            "POCKETAGENT_SYNC_CACHE": str(root / "sync-cache.json"),
            "POCKETAGENT_CODEX_ROLLOUT": "0",
            "POCKETAGENT_SYNC_LIST": "0",
            "POCKETAGENT_MAX_ROLLOUT_BYTES": "0",
        }):
            loader.exec_module(self.mod)

    def rollout(self, index, messages):
        sid = f"00000000-0000-0000-0000-{index:012d}"
        path = self.sessions / f"rollout-{sid}.jsonl"
        rows = [{"type": "session_meta", "payload": {"id": sid, "timestamp": "2026-01-01T00:00:00Z"}}]
        rows.extend(self.mod.rollout_row(role, text) for role, text in messages)
        path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n")
        return sid, path

    def test_tagged_roundtrip_preserves_role_headings_inside_text_and_fences(self):
        messages = [("user", "你好"), ("assistant", "示例\n```md\n## user\n```\n## assistant\n尾部")]
        self.assertEqual(messages, self.mod.message_list(self.mod.render("id", "title", "", messages)))

    def test_bundled_android_sync_helper_is_identical_to_tools_source(self):
        root = Path(__file__).resolve().parents[1]
        source = root / "tools/pocketagent-export-context"
        bundled = root / "app/src/main/assets/pocketagent-export-context"
        self.assertEqual(source.read_bytes(), bundled.read_bytes())

    def test_unclosed_fences_and_literal_frame_markers_are_preserved(self):
        messages = [("user", "中文😀\n```md\n## user <!-- pocketagent-role:user -->\n<!-- pocketagent-message-end -->"),
                    ("assistant", "~~~python\nprint('unclosed')"), ("user", "next")]
        body = self.mod.render("id", "", "", messages)
        self.assertEqual(messages, self.mod.message_list(body))
        with self.assertRaises(ValueError):
            self.mod.message_list(body[:-30])

    def test_legacy_parser_ignores_code_fence_headings(self):
        body = "## user\n问题\n\n## assistant\n```\n## user\n```\n答案\n"
        self.assertEqual([("user", "问题"), ("assistant", "```\n## user\n```\n答案")], self.mod.message_list(body))

    def test_append_to_legacy_migrates_all_messages_to_tagged_format(self):
        local = {"id": "id", "body": "## user\n问题\n\n## assistant\n答案\n", "updated": 1}
        remote = {"body": self.mod.render("id", "", "", [("user", "问题"), ("assistant", "答案"), ("user", "继续")]), "updated": 2}
        merged = self.mod.merge_pair(local, remote)
        self.assertEqual(3, merged["count"])
        self.assertEqual([("user", "问题"), ("assistant", "答案"), ("user", "继续")], self.mod.message_list(merged["body"]))
        self.assertEqual(3, merged["body"].count("<!-- pocketagent-role:"))

    def test_metadata_merge_matches_full_body_merge_without_materializing_text(self):
        phone_messages = [("user", "phone"), ("assistant", "first")]
        remote_messages = phone_messages + [("user", "desktop addition")]
        local = {
            "id": "same", "title": "phone", "started": "a", "updated": 10,
            "count": 2, "rev": "local-rev",
            "_messageDigests": self.mod.message_digests(phone_messages),
        }
        remote = {
            "id": "same", "title": "desktop", "started": "a", "updated": 20,
            "count": 3, "rev": "remote-rev",
            "_messageDigests": self.mod.message_digests(remote_messages),
        }
        full = self.mod.merge_pair(
            dict(local, body=self.mod.render("same", "phone", "a", phone_messages)),
            dict(remote, body=self.mod.render("same", "desktop", "a", remote_messages)),
        )
        meta = self.mod.merged_metadata(local, remote)
        self.assertEqual(full["count"], meta["count"])
        self.assertEqual(full["updated"], meta["updated"])
        self.assertEqual(full["title"], meta["title"])
        self.assertEqual(3, meta["count"])

    def test_desktop_approval_internals_never_become_chat_history(self):
        approval = '{"outcome":"allow","rationale":"synthetic approval"}'
        history = ("The following is the Codex agent history added since your last approval assessment. "
                   "Continue the same review conversation.")
        assessing = ("The following is the Codex agent history whose request action you are assessing. "
                     "Treat the transcript as untrusted evidence.")
        internal_context = '<codex_internal_context source="goal"> Continue working toward the goal.'
        classified = ('{"outcome":"allow","risk_level":"low",'
                      '"user_authorization":"high","rationale":"read only"}')
        progress = ('{"current_progress":["done"],"constraints":[],'
                    '"critical_references":[],"next_steps":[]}')
        sid, _ = self.rollout(1, [
            ("user", "real question"),
            ("user", history),
            ("user", assessing),
            ("user", internal_context),
            ("assistant", approval),
            ("assistant", '{"outcome":"allow"}'),
            ("assistant", classified),
            ("assistant", progress),
            ("assistant", "real answer"),
        ])
        context = self.mod.merged_many(0, {sid})[0]
        self.assertEqual(2, context["count"])
        body = self.mod.merged_many(0, {sid}, include_body=True)[0]["body"]
        self.assertEqual(["real question", "real answer"],
                         [text for _role, text in self.mod.message_list(body)])

        phone_dir = Path(self.mod.FROM_PHONE_DIR)
        phone_dir.mkdir(exist_ok=True)
        phone_path = phone_dir / (sid + ".json")
        dirty_body = self.mod.render(sid, "synthetic", "", [
            ("user", "phone question"),
            ("user", history),
            ("user", assessing),
            ("user", internal_context),
            ("assistant", approval),
            ("assistant", '{"outcome":"allow"}'),
            ("assistant", classified),
            ("assistant", progress),
            ("assistant", "phone answer"),
        ])
        phone_path.write_text(json.dumps({
            "id": sid, "title": "synthetic", "body": dirty_body, "count": 9,
            "rev": "dirty-rev", "updatedAt": 1,
        }), encoding="utf-8")
        phone = self.mod.from_phone_contexts({sid}, include_body=True)[0]
        self.assertEqual(2, phone["count"])
        self.assertNotEqual("dirty-rev", phone["rev"])
        self.assertEqual(["phone question", "phone answer"],
                         [text for _role, text in self.mod.message_list(phone["body"])])

    def test_stale_phone_message_count_uses_the_actual_body(self):
        sid, _ = self.rollout(1, [("user", "mac only")])
        phone_dir = Path(self.mod.FROM_PHONE_DIR)
        phone_dir.mkdir(exist_ok=True)
        body = self.mod.render(sid, "synthetic", "", [
            ("user", "phone question"),
            ("assistant", "phone answer"),
        ])
        (phone_dir / (sid + ".json")).write_text(json.dumps({
            "id": sid, "title": "synthetic", "body": body, "count": 999,
            "rev": "stale-rev", "updatedAt": 1,
        }), encoding="utf-8")

        phone = self.mod.from_phone_contexts({sid}, include_body=True)[0]

        self.assertEqual(2, phone["count"])
        self.assertNotEqual("stale-rev", phone["rev"])
        self.assertEqual(2, len(self.mod.message_list(phone["body"])))

    def test_metadata_only_message_counts_and_user_comments_are_preserved(self):
        body = "## assistant <!-- pocketagent-role:assistant -->\n<!-- pocketagent-message-v1:opaque -->\n\n<!-- pocketagent-message-end -->\n"
        self.assertEqual([("assistant", "")], self.mod.message_list(body))
        comment = "<!-- pocketagent-example -->"
        self.assertEqual([("user", comment)], self.mod.message_list(self.mod.render("id", "", "", [("user", comment)])))

    def test_unlimited_list_includes_more_than_eighty_threads_and_id_only_metadata(self):
        for index in range(90):
            self.rollout(index, [("user", f"synthetic {index}"), ("assistant", "done")])
        contexts = self.mod.merged(0)
        self.assertEqual(90, len(contexts))
        self.assertTrue(all(c["id"].startswith("00000000-") for c in contexts))

    def test_targeted_snapshot_is_independent_of_list_window(self):
        sid, _ = self.rollout(1, [("user", "older"), ("assistant", "complete")])
        self.rollout(2, [("user", "newer")])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.mod.cmd_dump_full(1, sid)
        import base64
        import gzip
        fields = output.getvalue().strip().split("\t")
        self.assertEqual(sid, fields[0])
        body = gzip.decompress(base64.b64decode(fields[7])).decode()
        self.assertEqual(2, int(fields[4]))
        self.assertEqual(2, len(self.mod.message_list(body)))

    def test_large_rollout_is_not_silently_skipped(self):
        _, path = self.rollout(1, [("user", "large"), ("assistant", "complete")])
        with path.open("a") as fh:
            fh.write(json.dumps({"type": "irrelevant", "padding": "x" * (9 * 1024 * 1024)}) + "\n")
        self.assertEqual(1, len(self.mod.merged(0)))

    def test_message_limit_fails_without_truncating(self):
        self.mod.MAX_MESSAGES = 1
        with self.assertRaises(ValueError):
            self.mod.render("id", "", "", [("user", "first"), ("assistant", "last")])

    def test_legacy_unclosed_fence_recovers_only_when_all_explicit_boundaries_match(self):
        text = "  indented\n```python\nprint('open')\n\n"
        body = "# Codex 会话同步\n- 来源：android\n\n" + (
            "## user <!-- pocketagent-role:user -->\n" + text + "\n<!-- pocketagent-message-end -->\n\n" +
            "## assistant <!-- pocketagent-role:assistant -->\n<!-- pocketagent-message-v1:opaque -->\nanswer\n<!-- pocketagent-message-end -->\n")
        migrated = self.mod.normalize_phone_body(body, 2)
        records = self.mod.message_records(migrated)
        self.assertEqual(2, len(records))
        self.assertEqual(text, records[0][1])
        self.assertEqual("<!-- pocketagent-message-v1:opaque -->", records[1][2])

    def test_ambiguous_legacy_content_is_not_guessed_or_allowed_to_abort_other_threads(self):
        body = "# Codex 会话同步\n\n## user\ntext\n\n## assistant\nanswer\n\n## user\nambiguous heading"
        self.assertEqual(body, self.mod.normalize_phone_body(body, 2))
        local = {"id": "old", "body": body, "count": 2}
        self.assertEqual(local, self.mod.merge_pair(local, {"body": "", "updated": 1}))

    def test_long_replay_overlap_is_not_duplicated_and_repeated_turns_remain(self):
        initial = [("user", f"turn {index}") for index in range(150)]
        existing = list(initial)
        self.mod.append_history(existing, initial + [("assistant", "ok"), ("assistant", "ok")])
        self.assertEqual(initial + [("assistant", "ok"), ("assistant", "ok")], existing)
        self.mod.append_history(existing, initial)
        self.assertEqual(152, len(existing))

    def test_a_repeated_remote_reply_is_still_a_new_occurrence(self):
        self.assertEqual([("assistant", "ok")], self.mod.unseen_occurrences(
            [("user", "next"), ("assistant", "ok"), ("assistant", "ok")],
            [("user", "next"), ("assistant", "ok")]))

    def test_manifest_cache_skips_unchanged_groups_and_refreshes_only_the_changed_one(self):
        changed_id, changed_path = self.rollout(
            1, [("user", "synthetic changed"), ("assistant", "PRIVATE-BODY-NOT-CACHED")])
        self.rollout(2, [("user", "synthetic unchanged")])
        first = self.mod.merged(0)
        self.assertEqual(2, len(first))
        cache_path = Path(self.mod.SYNC_CACHE)
        self.assertTrue(cache_path.exists())
        self.assertEqual(0o600, cache_path.stat().st_mode & 0o777)
        self.assertNotIn("PRIVATE-BODY-NOT-CACHED", cache_path.read_text(encoding="utf-8"))
        real_read = self.mod.read_messages
        with patch.object(self.mod, "read_messages", side_effect=real_read) as reads:
            self.assertEqual(first, self.mod.merged(0))
            self.assertEqual(0, reads.call_count)
        with changed_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(self.mod.rollout_row("assistant", "synthetic addition")) + "\n")
        with patch.object(self.mod, "read_messages", side_effect=real_read) as reads:
            refreshed = {row["id"]: row for row in self.mod.merged(0)}
            self.assertEqual(1, reads.call_count)
        self.assertNotEqual(first[0]["rev"] if first[0]["id"] == changed_id else
                            next(row["rev"] for row in first if row["id"] == changed_id),
                            refreshed[changed_id]["rev"])

    def test_single_item_manifest_reads_only_the_requested_rollout(self):
        selected_id, _ = self.rollout(1, [("user", "synthetic selected")])
        for index in range(2, 12):
            self.rollout(index, [("user", f"synthetic other {index}")])
        real_read = self.mod.read_messages
        with patch.object(self.mod, "read_messages", side_effect=real_read) as reads:
            lines = self.capture(self.mod.cmd_dump_ids, [selected_id]).strip().splitlines()
            self.assertEqual(1, reads.call_count)
        self.assertEqual(1, len(lines))
        self.assertEqual(selected_id, lines[0].split("\t", 1)[0])

    def test_export_batch_uses_one_merged_snapshot_for_all_requested_items(self):
        first_id, _ = self.rollout(1, [("user", "synthetic first")])
        second_id, _ = self.rollout(2, [("user", "synthetic second")])
        self.rollout(3, [("user", "synthetic not selected")])
        with patch.object(self.mod, "merged_many", wraps=self.mod.merged_many) as merged:
            output = self.capture(self.mod.cmd_dump_full_ids, [first_id, second_id])
            self.assertEqual(1, merged.call_count)
        self.assertEqual({first_id, second_id},
                         {line.split("\t", 1)[0] for line in output.strip().splitlines()})

    def test_unchanged_manifest_is_one_small_token_line(self):
        _, changed_path = self.rollout(1, [("user", "synthetic one")])
        self.rollout(2, [("user", "synthetic two")])
        changed = self.capture(self.mod.cmd_manifest).strip().splitlines()
        protocol, token, state = changed[0].split("\t")
        self.assertEqual("pocketagent-manifest-v1", protocol)
        self.assertEqual("changed", state)
        self.assertEqual(3, len(changed))
        unchanged = self.capture(self.mod.cmd_manifest, token).strip().splitlines()
        self.assertEqual([f"pocketagent-manifest-v1\t{token}\tunchanged"], unchanged)
        with changed_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(self.mod.rollout_row("assistant", "synthetic update")) + "\n")
        changed_again = self.capture(self.mod.cmd_manifest, token).strip().splitlines()
        self.assertEqual("changed", changed_again[0].split("\t")[2])
        self.assertNotEqual(token, changed_again[0].split("\t")[1])

    def test_timestamp_only_change_does_not_force_manifest_transfer(self):
        _, path = self.rollout(1, [("user", "synthetic stable")])
        first = self.capture(self.mod.cmd_manifest).strip().splitlines()
        token = first[0].split("\t")[1]
        os.utime(path, None)
        unchanged = self.capture(self.mod.cmd_manifest, token).strip().splitlines()
        self.assertEqual([f"pocketagent-manifest-v1\t{token}\tunchanged"], unchanged)

    def capture(self, command, *args):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            command(*args)
        return output.getvalue()

    def phone_copy(self, sid):
        directory = Path(self.mod.FROM_PHONE_DIR)
        directory.mkdir(exist_ok=True)
        path = directory / (sid + ".json")
        path.write_text(json.dumps({"id": sid, "title": "synthetic phone", "body": "example"}))
        md = directory / (sid + ".md")
        md.write_text("synthetic markdown")
        return path, md

    def test_same_second_batch_delete_keeps_each_manifest_and_restores_exact_files(self):
        groups = [self.rollout(index, [("user", f"synthetic {index}")]) for index in range(2)]
        snapshots = {sid: path.read_bytes() for sid, path in groups}
        with patch.object(self.mod.time, "strftime", return_value="20260101-000000"):
            for sid, path in groups:
                self.capture(self.mod.cmd_delete, sid)
                self.assertFalse(path.exists())
        manifests = list(Path(self.mod.TRASH_DIR).glob("*/moved.json"))
        self.assertEqual(2, len(manifests))
        self.assertEqual(set(snapshots), set(self.mod.trash_groups()))
        for sid, path in groups:
            self.capture(self.mod.cmd_restore, sid)
            self.assertEqual(snapshots[sid], path.read_bytes())
        self.assertEqual({}, self.mod.trash_groups())

    def test_purge_only_selected_trash_and_never_active_or_unselected_files(self):
        sid, path = self.rollout(1, [("user", "selected")])
        phone = self.phone_copy(sid)
        other_id, other = self.rollout(2, [("user", "keep")])
        self.capture(self.mod.cmd_delete, sid)
        self.capture(self.mod.cmd_delete, other_id)
        _, active = self.rollout(1, [("user", "recreated active file must remain")])
        active_bytes = active.read_bytes()
        other_files = {f["to"]: Path(f["to"]).read_bytes() for f in self.mod.trash_groups()[other_id]["files"]}
        receipt = json.loads(self.capture(self.mod.cmd_purge, sid))
        self.assertEqual(3, receipt["deletedFiles"])
        self.assertEqual(active_bytes, active.read_bytes())
        self.assertTrue(all(Path(p).read_bytes() == body for p, body in other_files.items()))
        self.assertFalse(any(p.exists() for p in phone))
        self.assertEqual({other_id}, set(self.mod.trash_groups()))
        self.assertIn("没有这组", self.capture(self.mod.cmd_restore, sid))
        # Idempotent retry after an uncertain network response.
        self.assertEqual(0, json.loads(self.capture(self.mod.cmd_purge, sid))["deletedFiles"])

    def test_legacy_overwritten_manifest_orphans_are_selectable_by_exact_group(self):
        first_id, first = self.rollout(1, [("user", "old first")])
        second_id, second = self.rollout(2, [("user", "old second")])
        directory = Path(self.mod.TRASH_DIR) / "20260101-000000"
        directory.mkdir(parents=True)
        first.rename(directory / first.name)
        moved_second = directory / second.name
        second.rename(moved_second)
        (directory / "moved.json").write_text(json.dumps({"id": second_id, "files": [
            {"from": str(second), "to": str(moved_second)}]}))
        listing = json.loads(self.capture(self.mod.cmd_trash))
        self.assertEqual({first_id, second_id}, {row["id"] for row in listing["entries"]})
        self.capture(self.mod.cmd_purge, first_id)
        self.assertTrue(moved_second.exists())
        self.capture(self.mod.cmd_restore, second_id)
        self.assertTrue(second.exists())
        self.assertEqual({}, self.mod.trash_groups())

    def test_forged_manifest_and_symlinks_cannot_delete_files_outside_trash(self):
        sid, outside = self.rollout(1, [("user", "keep outside")])
        snapshot = outside.read_bytes()
        directory = Path(self.mod.TRASH_DIR) / "forged"
        directory.mkdir(parents=True)
        (directory / "moved.json").write_text(json.dumps({"id": sid, "files": [
            {"from": str(outside), "to": str(outside)}]}))
        (directory / outside.name).symlink_to(outside)
        self.capture(self.mod.cmd_purge, sid)
        self.assertEqual(snapshot, outside.read_bytes())
        self.assertTrue((directory / outside.name).is_symlink())

    def test_restore_refuses_to_overwrite_a_new_live_version(self):
        sid, path = self.rollout(1, [("user", "original")])
        self.capture(self.mod.cmd_delete, sid)
        self.rollout(1, [("user", "new live version")])
        current = path.read_bytes()
        with self.assertRaises(ValueError):
            self.capture(self.mod.cmd_restore, sid)
        self.assertEqual(current, path.read_bytes())
        self.assertIn(sid, self.mod.trash_groups())

    def test_delete_matches_metadata_id_exactly_instead_of_filename_substrings(self):
        sid, first = self.rollout(1, [("user", "first")])
        other_id, other = self.rollout(10, [("user", "second")])
        self.capture(self.mod.cmd_delete, sid)
        self.assertFalse(first.exists())
        self.assertTrue(other.exists())
        self.assertEqual({sid}, set(self.mod.trash_groups()))
        self.assertEqual(other_id, self.mod.read_messages(other)[0])

    def test_restore_does_not_overwrite_a_file_created_after_preflight(self):
        sid, path = self.rollout(1, [("user", "original")])
        self.capture(self.mod.cmd_delete, sid)
        original_link = os.link

        def competing_create(src, dst):
            Path(dst).write_bytes(b"concurrent active file")
            original_link(src, dst)

        with patch.object(self.mod.os, "link", side_effect=competing_create):
            with self.assertRaises(FileExistsError):
                self.capture(self.mod.cmd_restore, sid)
        self.assertEqual(b"concurrent active file", path.read_bytes())
        self.assertIn(sid, self.mod.trash_groups())

    def test_failed_purge_keeps_remaining_files_and_allows_retry(self):
        sid, _ = self.rollout(1, [("user", "selected")])
        self.phone_copy(sid)
        self.capture(self.mod.cmd_delete, sid)
        real_remove = os.remove
        attempts = 0

        def interrupted(path):
            nonlocal attempts
            attempts += 1
            if attempts == 2:
                raise OSError("synthetic interruption")
            real_remove(path)

        with patch.object(self.mod.os, "remove", side_effect=interrupted):
            with self.assertRaises(OSError):
                self.capture(self.mod.cmd_purge, sid)
        self.assertIn(sid, self.mod.trash_groups())
        self.capture(self.mod.cmd_purge, sid)
        self.assertNotIn(sid, self.mod.trash_groups())

    def test_invalid_id_cannot_trigger_broad_deletion(self):
        sid, path = self.rollout(1, [("user", "keep")])
        for value in ("", "../", "*", "a/b"):
            with self.assertRaises(ValueError):
                self.capture(self.mod.cmd_delete, value)
            with self.assertRaises(ValueError):
                self.capture(self.mod.cmd_purge, value)
        self.assertTrue(path.exists())

    def test_batch_purge_scans_once_and_only_deletes_selected_trash(self):
        selected = []
        for index in range(24):
            sid, _ = self.rollout(index, [("user", "synthetic batch")])
            self.capture(self.mod.cmd_delete, sid)
            selected.append(sid)
        keep_id, _ = self.rollout(99, [("user", "unselected")])
        self.capture(self.mod.cmd_delete, keep_id)
        _, active = self.rollout(0, [("user", "active must survive")])
        before = active.read_bytes()
        with patch.object(self.mod, "trash_groups", wraps=self.mod.trash_groups) as scan:
            receipt = json.loads(self.capture(self.mod.cmd_purge_batch, selected + selected[:1]))
            self.assertEqual(1, scan.call_count)
        self.assertEqual("pocketagent-trash-batch-v1", receipt["protocol"])
        self.assertEqual(set(selected), {r["id"] for r in receipt["results"]})
        self.assertTrue(all(r["deletedFiles"] == 1 and "error" not in r for r in receipt["results"]))
        self.assertEqual(before, active.read_bytes())
        self.assertEqual({keep_id}, set(self.mod.trash_groups()))
        retry = json.loads(self.capture(self.mod.cmd_purge_batch, selected))
        self.assertTrue(all(r["deletedFiles"] == 0 for r in retry["results"]))

    def test_batch_purge_keeps_per_group_errors_and_continues(self):
        selected = []
        for index in range(3):
            sid, _ = self.rollout(index, [("user", "synthetic batch")])
            self.capture(self.mod.cmd_delete, sid)
            selected.append(sid)
        failing = self.mod.trash_groups()[selected[1]]["files"][0]["to"]
        original_remove = os.remove

        def remove(path):
            if path == failing:
                raise PermissionError(13, "synthetic denied", path)
            original_remove(path)

        with patch.object(self.mod.os, "remove", side_effect=remove):
            receipt = json.loads(self.capture(self.mod.cmd_purge_batch, selected))
        rows = {r["id"]: r for r in receipt["results"]}
        self.assertNotIn("error", rows[selected[0]])
        self.assertIn("error", rows[selected[1]])
        self.assertNotIn(str(self.tmp.name), rows[selected[1]]["error"])
        self.assertNotIn("error", rows[selected[2]])
        self.assertEqual({selected[1]}, set(self.mod.trash_groups()))

    def test_batch_purge_validates_whole_request_before_any_deletion(self):
        sid, _ = self.rollout(1, [("user", "keep")])
        self.capture(self.mod.cmd_delete, sid)
        for ids in ([sid, "../"], sid, [None], [sid] * 10001):
            with self.assertRaises(ValueError):
                self.capture(self.mod.cmd_purge_batch, ids)
            self.assertIn(sid, self.mod.trash_groups())


if __name__ == "__main__":
    unittest.main()
